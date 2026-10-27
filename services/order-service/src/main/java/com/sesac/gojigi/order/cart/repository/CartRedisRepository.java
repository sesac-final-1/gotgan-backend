package com.sesac.gojigi.order.cart.repository;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 장바구니 캐시 Redis Hash {@code cart:{userId}} (field = 옵션 ID, value = "수량:선택(1|0)") — 원본은 MySQL cart_item (가이드라인 09장).
 * 쓰기 실패(연결 장애 · maxmemory noeviction 의 OOM 거절)는 WARN 만, 읽기 실패는 미스와 같다 — 장바구니는 MySQL 로 정상 동작한다 (서킷 redisCart).
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class CartRedisRepository {

    private static final String CIRCUIT = "redisCart";

    private final StringRedisTemplate redis;

    /** 캐시 1줄 — 수량 · 선택 여부 */
    public record CachedLine(int quantity, boolean checked) {

        String encode() {
            return quantity + ":" + (checked ? 1 : 0);
        }

        /** "3:1" — 선택 표시가 없는 예전 값("3")은 선택된 것으로 */
        static CachedLine decode(String value) {
            int sep = value.indexOf(':');
            return sep < 0 ? new CachedLine(Integer.parseInt(value), true)
                    : new CachedLine(Integer.parseInt(value.substring(0, sep)), value.endsWith(":1"));
        }
    }

    /** 캐시 읽기 — 키가 없으면(미스) · Redis 장애면 empty → MySQL 원본. 옵션 ID 순 */
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "findFallback")
    public Optional<Map<Long, CachedLine>> find(Long userId) {
        Map<Object, Object> entries = redis.opsForHash().entries(key(userId));
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, CachedLine> lines = new TreeMap<>();
        entries.forEach((field, value) -> lines.put(Long.valueOf(field.toString()), CachedLine.decode(value.toString())));
        return Optional.of(lines);
    }

    Optional<Map<Long, CachedLine>> findFallback(Long userId, Throwable t) {
        log.warn("장바구니 Redis 읽기 실패 — MySQL 원본으로 - userId={}, reason={}", userId, t.toString());
        return Optional.empty();
    }

    /**
     * MySQL 커밋 뒤(또는 읽기 미스 뒤) 그 회원 장바구니 전체로 바꿔 쓴다. 지우기를 먼저 해서 채우기가 거절되면(OOM) 키가 없는 상태 —
     * 읽기는 미스로 MySQL 을 본다. DEL 은 noeviction 에서도 거절되지 않는다.
     */
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "replaceFallback")
    public void replace(Long userId, Map<Long, CachedLine> lines) {
        String key = key(userId);
        redis.delete(key);
        if (!lines.isEmpty()) {
            redis.opsForHash().putAll(key, lines.entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().toString(), e -> e.getValue().encode())));
        }
    }

    void replaceFallback(Long userId, Map<Long, CachedLine> lines, Throwable t) {
        log.warn("장바구니 Redis 갱신 실패 — MySQL 원본으로 계속 - userId={}, reason={}", userId, t.toString());
    }

    static String key(Long userId) {
        return "cart:" + userId;
    }
}
