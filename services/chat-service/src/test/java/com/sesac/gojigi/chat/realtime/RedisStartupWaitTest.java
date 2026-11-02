package com.sesac.gojigi.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * 기동 시 Redis 대기 — 클러스터 생성 직후 Redis 가 늦게 준비되면 리스너 컨테이너가 첫 연결 실패로 기동을 실패시켜
 * 파드가 한 번 재시작하던 문제(11/02). 컨테이너를 만들기 전에 PING 이 될 때까지 기다린다
 */
class RedisStartupWaitTest {

    private final RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
    private final RedisConnection connection = mock(RedisConnection.class);

    @Test
    @DisplayName("처음 두 번 연결 실패 → 세 번째에 PING 성공하면 true, 연결은 닫는다")
    void waitsUntilRedisAnswers() {
        when(factory.getConnection())
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"))
                .thenReturn(connection);

        assertThat(RealtimeConfig.awaitRedis(factory, 5, Duration.ZERO)).isTrue();

        verify(factory, times(3)).getConnection();
        verify(connection).ping();
        verify(connection).close();
    }

    @Test
    @DisplayName("끝까지 연결이 안 되면 횟수만큼 시도하고 false — 그 뒤는 컨테이너가 기동 실패(기존 동작)")
    void givesUpAfterAttempts() {
        when(factory.getConnection()).thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

        assertThat(RealtimeConfig.awaitRedis(factory, 3, Duration.ZERO)).isFalse();

        verify(factory, times(3)).getConnection();
    }

    @Test
    @DisplayName("기다리는 중 인터럽트되면 바로 false · 인터럽트 상태는 남긴다")
    void stopsWhenInterrupted() {
        when(factory.getConnection()).thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));
        Thread.currentThread().interrupt();

        boolean ready = RealtimeConfig.awaitRedis(factory, 3, Duration.ofSeconds(1));

        assertThat(ready).isFalse();
        assertThat(Thread.interrupted()).isTrue(); // 상태 확인 겸 초기화
        verify(factory, times(1)).getConnection();
    }
}
