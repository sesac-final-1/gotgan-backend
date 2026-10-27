package com.sesac.gojigi.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * Redis 모드 — 파드 간 중계(chat:room:*) · 접속 중 표시(ZSET 만료 시각) · 차단 목록. 스프링 컨텍스트 없이 Redis 컨테이너에 직접 붙는다.
 * 시계는 직접 움직여 "파드가 죽어 지우지 못한 항목은 TTL 뒤 무시"를 확인
 */
class ChatRedisTest {

    private static GenericContainer<?> redisContainer;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-27T05:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    @BeforeAll
    static void startRedis() {
        redisContainer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        redisContainer.start();
        connectionFactory = new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        redisContainer.stop();
    }

    @BeforeEach
    void flush() {
        redis.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
    }

    @Test
    @DisplayName("접속 중 표시: 들어오면 다른 파드에서도 보이고, 나가거나 끊기면 바로 사라진다")
    void presenceAcrossPods() {
        ChatPresence podA = new ChatPresence(redis, clock);
        ChatPresence podB = new ChatPresence(redis, clock);

        podA.enter("s1", "sub-1", 7L, "STORE#5");
        podA.enter("s1", "sub-2", 8L, "STORE#5");

        assertThat(podB.isWatching(7L, "STORE#5")).isTrue();
        assertThat(podB.isWatching(7L, "USER#10")).isFalse();
        assertThat(redis.getExpire(ChatPresence.KEY_PREFIX + 7)).isPositive();

        podA.leave("s1", "sub-1");
        assertThat(podB.isWatching(7L, "STORE#5")).isFalse();
        assertThat(podB.isWatching(8L, "STORE#5")).isTrue();

        podA.leaveAll("s1");
        assertThat(podB.isWatching(8L, "STORE#5")).isFalse();
        assertThat(podA.localCount()).isZero();
        podA.leave("unknown", "sub-9");   // 모르는 연결은 무시
    }

    @Test
    @DisplayName("파드가 죽어 지우지 못한 항목은 TTL 뒤 무시 — 살아 있는 구독은 refresh 로 연장, 종료 시 clear 로 바로 지움")
    void staleEntriesExpireAndLiveOnesRefresh() {
        ChatPresence crashed = new ChatPresence(redis, clock);
        ChatPresence alive = new ChatPresence(redis, clock);
        crashed.enter("dead", "sub", 7L, "USER#10");
        alive.enter("live", "sub", 7L, "STORE#5");

        now.set(now.get().plus(ChatPresence.TTL.dividedBy(2)));
        alive.refresh();
        now.set(now.get().plus(ChatPresence.TTL.dividedBy(2)).plusSeconds(1));

        assertThat(alive.isWatching(7L, "USER#10")).isFalse();   // 연장되지 않은 항목은 만료
        assertThat(alive.isWatching(7L, "STORE#5")).isTrue();
        alive.refresh();
        assertThat(redis.opsForZSet().size(ChatPresence.KEY_PREFIX + 7)).isEqualTo(1);   // 만료 항목 정리

        alive.clear();
        assertThat(crashed.isWatching(7L, "STORE#5")).isFalse();
    }

    @Test
    @DisplayName("중계: chat:room:{id} 발행 → 패턴 구독 하나로 받아 이 파드 브로커 /sub/rooms/{id} 로")
    void relayThroughRedis() throws Exception {
        SimpMessagingTemplate broker = mock(SimpMessagingTemplate.class);
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(RealtimeConfig.listener(broker), new PatternTopic(RealtimeConfig.CHANNEL_PREFIX + "*"));
        container.afterPropertiesSet();
        container.start();
        try {
            ChatRelay relay = new RealtimeConfig.Redis().redisRelay(redis);
            await().untilAsserted(() -> {
                relay.publish(42L, "{\"type\":\"MESSAGE\"}");
                verify(broker, timeout(500).atLeastOnce()).convertAndSend("/sub/rooms/42", "{\"type\":\"MESSAGE\"}");
            });
        } finally {
            container.stop();
            container.destroy();
        }
    }

    @Test
    @DisplayName("차단 목록: blocked:user:{id} 가 있으면 차단 · local 모드는 확인 안 함")
    void blockedUsers() {
        redis.opsForValue().set(BlockedUsers.KEY_PREFIX + 10, "1");

        assertThat(new BlockedUsers(redis).isBlocked(10L)).isTrue();
        assertThat(new BlockedUsers(redis).isBlocked(11L)).isFalse();
        assertThat(new BlockedUsers(null).isBlocked(10L)).isFalse();
    }

    @Test
    @DisplayName("Redis 가 안 되면: 표시 · 중계 · 차단 확인 모두 예외 없이 넘어가고, 접속 중 확인은 '보고 있지 않음'(알림을 보냄)")
    void redisDownIsTolerated() {
        LettuceConnectionFactory down = new LettuceConnectionFactory("localhost", 1);
        down.afterPropertiesSet();
        down.start();
        StringRedisTemplate broken = new StringRedisTemplate(down);
        try {
            ChatPresence presence = new ChatPresence(broken, clock);
            presence.enter("s1", "sub", 7L, "USER#10");
            presence.refresh();
            assertThat(presence.isWatching(7L, "USER#10")).isFalse();
            presence.leaveAll("s1");
            assertThat(new BlockedUsers(broken).isBlocked(10L)).isFalse();
            new RealtimeConfig.Redis().redisRelay(broken).publish(1L, "{}");
        } finally {
            down.destroy();
        }
    }
}
