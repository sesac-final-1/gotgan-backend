package com.sesac.gojigi.gateway.filter;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 현재 접속자 기록 (11/17, 관리자 시스템 상태 화면) — 요청한 사람의 "마지막 요청 시각"을 Redis 정렬 집합에 남긴다.
 * - gotgan:active:visitors: 회원 u:{userId}, 비회원 ip:{X-Vercel-Forwarded-For} 또는 direct (요청 제한 키와 같은 규칙)
 * - gotgan:active:members: 로그인 회원만
 * admin-service VisitorService 가 최근 2분 안의 수를 센다. 부하테스트(k6 가 X-Gotgan-Loadtest 헤더를 붙임)와 액추에이터는 기록하지 않는다.
 * 요청을 기다리게 하지 않는다(기록은 따로 보내고 실패해도 무시). 같은 사람은 15초에 한 번만 기록해 Redis 쓰기를 줄인다.
 */
@Slf4j
@Component
public class ActiveUserFilter implements GlobalFilter, Ordered {

    static final String VISITORS_KEY = "gotgan:active:visitors";
    static final String MEMBERS_KEY = "gotgan:active:members";
    static final String LOADTEST_HEADER = "X-Gotgan-Loadtest";
    static final long THROTTLE_SECONDS = 15;
    private static final int MAX_CACHED = 20_000;

    private final ReactiveStringRedisTemplate redis;
    private final Clock clock;
    private final Map<String, Long> lastWritten = new ConcurrentHashMap<>();

    @Autowired
    public ActiveUserFilter(ReactiveStringRedisTemplate redis) {
        this(redis, Clock.systemUTC());
    }

    ActiveUserFilter(ReactiveStringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (skip(exchange)) {
            return chain.filter(exchange);
        }
        return AuthenticatedUser.current()
                .filter(AuthenticatedUser::linked)
                .map(user -> "u:" + user.userId())
                .defaultIfEmpty("")
                .doOnNext(member -> record(member.isEmpty() ? RateLimitKeyResolver.anonymousKey(exchange.getRequest().getHeaders()) : member,
                        !member.isEmpty()))
                .then(chain.filter(exchange));
    }

    static boolean skip(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        return exchange.getRequest().getHeaders().containsHeader(LOADTEST_HEADER) || path.startsWith("/actuator");
    }

    /** 따로 보내고 기다리지 않는다 — Redis 가 느리거나 죽어도 요청은 그대로 */
    void record(String visitor, boolean member) {
        long now = clock.instant().getEpochSecond();
        Long last = lastWritten.get(visitor);
        if (last != null && now - last < THROTTLE_SECONDS) {
            return;
        }
        if (lastWritten.size() > MAX_CACHED) {
            lastWritten.clear();
        }
        lastWritten.put(visitor, now);
        Mono<Boolean> write = redis.opsForZSet().add(VISITORS_KEY, visitor, now);
        if (member) {
            write = write.then(redis.opsForZSet().add(MEMBERS_KEY, visitor, now));
        }
        write.subscribe(ok -> { }, e -> log.debug("접속자 기록 실패 - {}", e.getMessage()));
    }

    /** 요청 제한, 인증 뒤, 라우팅 전 */
    @Override
    public int getOrder() {
        return 0;
    }
}
