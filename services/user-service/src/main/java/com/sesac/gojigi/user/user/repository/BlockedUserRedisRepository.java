package com.sesac.gojigi.user.user.repository;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 차단 목록 — Redis blocked:user:{userId} = 사유 (컨벤션 2-2). 원본 없음(Redis 전용), TTL 이 지나면 사라진다.
 * gateway BlockedUserFilter 가 서명 검증을 통과한 토큰의 user_id 로 확인해 401 — 탈퇴 · 이용정지 회원의 남은 Access Token 을 막는다.
 * TTL 은 Keycloak realm 의 Access Token 수명과 같게 (app.auth.blocked-user-ttl, realm accessTokenLifespan 을 바꾸면 같이).
 * 키가 회원 번호 기준이라 정지 해제 때는 unblock — 남겨 두면 해제 후 새로 로그인해도 TTL 동안 401.
 */
@Repository
public class BlockedUserRedisRepository {

    public static final String WITHDRAWN = "withdrawn";
    public static final String SUSPENDED = "suspended";

    private static final String KEY_PREFIX = "blocked:user:";   // gateway BlockedUserFilter 와 같은 키

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public BlockedUserRedisRepository(StringRedisTemplate redis,
            @Value("${app.auth.blocked-user-ttl}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    public void block(Long userId, String reason) {
        redis.opsForValue().set(key(userId), reason, ttl);
    }

    public void unblock(Long userId) {
        redis.delete(key(userId));
    }

    public Duration ttl() {
        return ttl;
    }

    private static String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
