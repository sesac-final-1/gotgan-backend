package com.sesac.gojigi.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sesac.gojigi.admin.system.dto.response.LoadTestResponses.Visitors;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

class VisitorServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-17T05:00:00Z");

    @Test
    @DisplayName("최근 1분 안의 접속자, 회원 수 — 오래된 항목은 지우고 센다")
    @SuppressWarnings("unchecked")
    void counts() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.zCard(VisitorService.VISITORS_KEY)).thenReturn(12L);
        when(zset.zCard(VisitorService.MEMBERS_KEY)).thenReturn(5L);

        Visitors v = new VisitorService(redis, Clock.fixed(NOW, ZoneId.of("Asia/Seoul"))).visitors();

        assertThat(v).isEqualTo(new Visitors(12L, 5L, 60));
        verify(zset).removeRangeByScore(VisitorService.VISITORS_KEY, 0, NOW.getEpochSecond() - 60);
    }

    @Test
    @DisplayName("Redis 실패면 null")
    @SuppressWarnings("unchecked")
    void redisDown() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.removeRangeByScore(anyString(), anyDouble(), anyDouble())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(new VisitorService(redis, Clock.fixed(NOW, ZoneId.of("Asia/Seoul"))).visitors()).isEqualTo(new Visitors(null, null, 60));
    }
}
