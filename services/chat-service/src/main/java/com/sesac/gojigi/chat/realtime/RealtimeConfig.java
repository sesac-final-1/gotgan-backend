package com.sesac.gojigi.chat.realtime;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * 파드 간 중계 · 접속 중 표시 · 차단 목록 — app.chat.realtime 으로 고른다.
 * redis(기본): 클러스터 / local: Redis 없이 이 JVM 안에서만(테스트 · 로컬)
 */
public final class RealtimeConfig {

    static final String CHANNEL_PREFIX = "chat:room:";

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

        /** 구독은 패턴 하나(chat:room:*) — 방이 늘어도 Redis 연결 · 구독 수는 그대로 */
        @Bean
        RedisMessageListenerContainer chatRelayListener(RedisConnectionFactory connectionFactory, SimpMessagingTemplate broker) {
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

    /** 채널 chat:room:{id} → 이 파드 브로커 /sub/rooms/{id}. 그 방 구독자가 이 파드에 없으면 브로커가 버린다 */
    static MessageListener listener(SimpMessagingTemplate broker) {
        return (message, pattern) -> broker.convertAndSend(
                ChatRelay.DESTINATION_PREFIX + new String(message.getChannel(), UTF_8).substring(CHANNEL_PREFIX.length()),
                new String(message.getBody(), UTF_8));
    }
}
