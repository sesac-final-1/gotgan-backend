package com.sesac.gojigi.chat.realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 탈퇴 회원의 남은 토큰 차단 — 게이트웨이 BlockedUserFilter 와 같은 키 blocked:user:{id}. WebSocket CONNECT 는 게이트웨이 인증을 거치지 않아서 여기서 본다.
 * Redis 오류면 통과(fail-open, 게이트웨이와 같음). redis 가 null 이면(local 모드) 확인하지 않음
 */
@Slf4j
public class BlockedUsers {

    static final String KEY_PREFIX = "blocked:user:";

    private final StringRedisTemplate redis;

    public BlockedUsers(StringRedisTemplate redis) {
        this.redis = redis;
    }

    boolean isBlocked(Long userId) {
        if (redis == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + userId));
        } catch (RuntimeException e) {
            log.warn("차단 목록 조회 실패 — 확인 없이 통과 userId={}, cause={}", userId, e.toString());
            return false;
        }
    }
}
