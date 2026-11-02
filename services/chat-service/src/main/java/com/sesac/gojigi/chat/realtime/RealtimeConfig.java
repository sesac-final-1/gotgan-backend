package com.sesac.gojigi.chat.realtime;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.time.Clock;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * 파드 간 중계 · 접속 중 표시 · 차단 목록 — app.chat.realtime 으로 고른다.
 * redis(기본): 클러스터 / local: Redis 없이 이 JVM 안에서만(테스트 · 로컬)
 */
@Slf4j
public final class RealtimeConfig {

    static final String CHANNEL_PREFIX = "chat:room:";
    /** 기동 시 Redis 를 기다리는 최대 횟수 × 간격 — 클러스터 생성 직후 Redis(DB 노드) · DNS 가 늦게 준비되는 1분 남짓을 덮는다 */
    static final int STARTUP_ATTEMPTS = 30;
    static final Duration STARTUP_INTERVAL = Duration.ofSeconds(2);

    private RealtimeConfig() {
    }

    @Slf4j
    @Configuration
    @ConditionalOnProperty(name = "app.chat.realtime", havingValue = "redis", matchIfMissing = true)
    static class Redis {

        /** 발행 실패면 실시간 전달만 빠지고 메시지는 저장돼 있다 — 화면이 다시 연결하면 REST 로 채운다 */
        @Bean
        ChatRelay redisRelay(StringRedisTemplate redis) {
            return (roomId, json) -> {
                try {
                    redis.convertAndSend(CHANNEL_PREFIX + roomId, json);
                } catch (RuntimeException e) {
                    log.warn("채팅 중계 발행 실패 — 메시지는 저장됨 roomId={}, cause={}", roomId, e.toString());
                }
            };
        }

        /**
         * 구독은 패턴 하나(chat:room:*) — 방이 늘어도 Redis 연결 · 구독 수는 그대로.
         * 컨테이너는 첫 구독 연결이 실패하면 재시도 없이 기동을 실패시키므로(끊긴 뒤 재연결만 재시도) 만들기 전에 Redis 응답을 기다린다
         */
        @Bean
        RedisMessageListenerContainer chatRelayListener(RedisConnectionFactory connectionFactory, SimpMessagingTemplate broker) {
            awaitRedis(connectionFactory, STARTUP_ATTEMPTS, STARTUP_INTERVAL);
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(connectionFactory);
            container.addMessageListener(listener(broker), new PatternTopic(CHANNEL_PREFIX + "*"));
            return container;
        }

        @Bean
        ChatPresence redisPresence(StringRedisTemplate redis, Clock clock) {
            return new ChatPresence(redis, clock);
        }

        @Bean
        BlockedUsers redisBlockedUsers(StringRedisTemplate redis) {
            return new BlockedUsers(redis);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "app.chat.realtime", havingValue = "local")
    static class Local {

        @Bean
        ChatRelay localRelay(SimpMessagingTemplate broker) {
            return (roomId, json) -> broker.convertAndSend(ChatRelay.DESTINATION_PREFIX + roomId, json);
        }

        @Bean
        ChatPresence localPresence(Clock clock) {
            return new ChatPresence(null, clock);
        }

        @Bean
        BlockedUsers localBlockedUsers() {
            return new BlockedUsers(null);
        }
    }

    /**
     * Redis PING 이 될 때까지 간격마다 다시 시도. 끝내 안 되면 false — 그대로 진행해 컨테이너가 기동 실패(파드 재시작)하게 둔다
     */
    static boolean awaitRedis(RedisConnectionFactory connectionFactory, int attempts, Duration interval) {
        for (int i = 1; i <= attempts; i++) {
            try (RedisConnection connection = connectionFactory.getConnection()) {
                connection.ping();
                return true;
            } catch (RedisConnectionFailureException e) {
                log.warn("Redis 대기 중 ({}/{}) — cause={}", i, attempts, e.toString());
                if (i < attempts && !sleep(interval)) {
                    return false;
                }
            }
        }
        return false;
    }

    private static boolean sleep(Duration interval) {
        try {
            Thread.sleep(interval);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 채널 chat:room:{id} → 이 파드 브로커 /sub/rooms/{id}. 그 방 구독자가 이 파드에 없으면 브로커가 버린다 */
    static MessageListener listener(SimpMessagingTemplate broker) {
        return (message, pattern) -> broker.convertAndSend(
                ChatRelay.DESTINATION_PREFIX + new String(message.getChannel(), UTF_8).substring(CHANNEL_PREFIX.length()),
                new String(message.getBody(), UTF_8));
    }
}
