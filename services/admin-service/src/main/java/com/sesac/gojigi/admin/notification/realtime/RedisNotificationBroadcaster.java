package com.sesac.gojigi.admin.notification.realtime;

import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 파드 간 전달 (기본) — 알림을 채널 notify:{받는 쪽} 에 발행하고, 모든 파드가 notify:* 를 구독해 자기 연결에 전달한다.
 * Redis 가 잠깐 안 되면 실시간 전달만 빠지고 알림함에는 이미 쌓여 있다(화면이 다시 연결 · 새로고침하면 보임).
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.notification.realtime", havingValue = "redis", matchIfMissing = true)
public class RedisNotificationBroadcaster {

    static final String CHANNEL_PREFIX = "notify:";

    @Bean
    NotificationBroadcaster redisBroadcaster(StringRedisTemplate redis) {
        return (recipient, json) -> {
            try {
                redis.convertAndSend(CHANNEL_PREFIX + recipient, json);
            } catch (RuntimeException e) {
                log.warn("실시간 알림 발행 실패 — 알림함에는 저장됨 recipient={}, cause={}", recipient, e.toString());
            }
        };
    }

    @Bean
    RedisMessageListenerContainer notificationListener(RedisConnectionFactory connectionFactory, NotificationStreams streams) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> streams.deliver(
                new String(message.getChannel(), StandardCharsets.UTF_8).substring(CHANNEL_PREFIX.length()),
                new String(message.getBody(), StandardCharsets.UTF_8)), new PatternTopic(CHANNEL_PREFIX + "*"));
        return container;
    }
}
