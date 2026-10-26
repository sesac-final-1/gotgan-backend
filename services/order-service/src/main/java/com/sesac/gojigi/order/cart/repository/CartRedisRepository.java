package com.sesac.gojigi.order.cart.repository;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 장바구니 캐시 Redis Hash {@code cart:{userId}} (field = 옵션 ID, value = 수량) — 원본은 MySQL cart_item (가이드라인 09장).
 * 쓰기 실패(연결 장애 · maxmemory noeviction 의 OOM 거절)는 WARN 만 — 장바구니는 MySQL 로 정상 동작한다 (서킷 redisCart).
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class CartRedisRepository {

    private static final String CIRCUIT = "redisCart";

    private final StringRedisTemplate redis;

    /**
     * MySQL 커밋 뒤 그 회원 장바구니 전체로 바꿔 쓴다. 지우기를 먼저 해서 채우기가 거절되면(OOM) 키가 없는 상태 — 읽기는 미스로 MySQL 을 본다.
     * DEL 은 noeviction 에서도 거절되지 않는다.
     */
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "replaceFallback")
    public void replace(Long userId, Map<Long, Integer> quantities) {
        String key = key(userId);
        redis.delete(key);
        if (!quantities.isEmpty()) {
            redis.opsForHash().putAll(key, quantities.entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().toString(), e -> e.getValue().toString())));
        }
    }

    void replaceFallback(Long userId, Map<Long, Integer> quantities, Throwable t) {
        log.warn("장바구니 Redis 갱신 실패 — MySQL 원본으로 계속 - userId={}, reason={}", userId, t.toString());
    }

    static String key(Long userId) {
        return "cart:" + userId;
    }
}
