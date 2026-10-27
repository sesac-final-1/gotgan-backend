package com.sesac.gojigi.admin.notification.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 실시간 전달 — 파드 안 연결 관리 · 로컬 전달 · Redis 발행 · 구독 → 연결 */
class NotificationRealtimeTest {

    /** 보낸 이벤트 수를 세는 연결 */
    static class CountingEmitter extends SseEmitter {
        int sent;

        @Override
        public void send(SseEventBuilder builder) {
            sent++;
        }
    }

    @Test
    @DisplayName("연결 · 전달 · 연결 수 — 받는 쪽마다 그 연결에만, 한 연결이 여러 받는 쪽을 들어도 연결 수는 1")
    void streams_deliver() {
        NotificationStreams streams = new NotificationStreams(Duration.ofHours(1));
        SseEmitter seller = streams.open(List.of("USER#2", "STORE#1"));
        SseEmitter buyer = streams.open(List.of("USER#4"));

        streams.deliver("STORE#1", "{\"title\":\"정산\"}");
        streams.deliver("USER#9", "{}");   // 듣는 연결 없음 — 무시

        assertThat(streams.connections()).isEqualTo(2);
        assertThat(seller).isNotNull();
        assertThat(buyer).isNotNull();
        streams.close();
    }

    @Test
    @DisplayName("로컬 전달 — 발행하면 이 파드 연결에 바로")
    void local_publish() {
        NotificationStreams streams = mock(NotificationStreams.class);

        new LocalNotificationBroadcaster(streams).publish("USER#4", "{}");

        verify(streams).deliver("USER#4", "{}");
    }

    @Test
    @DisplayName("Redis — notify:{받는 쪽} 으로 발행(실패해도 예외 없음), 구독 메시지는 채널에서 받는 쪽을 꺼내 연결로")
    void redis_publishAndListen() {
        RedisNotificationBroadcaster config = new RedisNotificationBroadcaster();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        NotificationBroadcaster broadcaster = config.redisBroadcaster(redis);

        broadcaster.publish("STORE#1", "{\"a\":1}");
        verify(redis).convertAndSend("notify:STORE#1", "{\"a\":1}");

        willThrow(new IllegalStateException("redis down")).given(redis).convertAndSend(eq("notify:USER#4"), any(String.class));
        broadcaster.publish("USER#4", "{}");   // 알림함엔 이미 저장 — 실시간만 빠짐

        NotificationStreams streams = mock(NotificationStreams.class);
        RedisMessageListenerContainer container = config.notificationListener(mock(RedisConnectionFactory.class), streams);
        assertThat(container).isNotNull();
        MessageListener listener = RedisNotificationBroadcaster.listener(streams);
        listener.onMessage(new DefaultMessage("notify:USER#4".getBytes(StandardCharsets.UTF_8), "{\"b\":2}".getBytes(StandardCharsets.UTF_8)), null);

        verify(streams).deliver("USER#4", "{\"b\":2}");
    }
}
