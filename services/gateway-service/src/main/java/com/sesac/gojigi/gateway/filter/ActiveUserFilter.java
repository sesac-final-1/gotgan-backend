package com.sesac.gojigi.gateway.filter;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
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
 * - gotgan:active:visitors: 브라우저마다 방문자 ID v:{X-Gotgan-Visitor}(없으면 요청 제한 키 ip:… / direct). 로그인 전후 같은 사람
 * - gotgan:active:members: 로그인 회원 u:{userId}
 * admin-service VisitorService 가 최근 1분 안의 수를 센다. 화면은 20초마다 접속 신호를, 나갈 때 leave 를 보낸다(PresenceWebFilter). 부하테스트(k6 가 X-Gotgan-Loadtest 헤더를 붙임)와 액추에이터는 기록하지 않는다.
 * 요청을 기다리게 하지 않는다(기록은 따로 보내고 실패해도 무시). 같은 사람은 15초에 한 번만 기록해 Redis 쓰기를 줄인다.
 */
@Slf4j
@Component
public class ActiveUserFilter implements GlobalFilter, Ordered {

    static final String VISITORS_KEY = "gotgan:active:visitors";
    static final String MEMBERS_KEY = "gotgan:active:members";
    static final String LOADTEST_HEADER = "X-Gotgan-Loadtest";
    static final String LOGOUT_PATH = "/api/auth/logout";
    static final String VISITOR_HEADER = "X-Gotgan-Visitor";
    private static final Pattern VISITOR_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
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
        boolean logout = LOGOUT_PATH.equals(exchange.getRequest().getPath().value());
        String visitor = visitorKey(exchange);
        return memberKey()
                .doOnNext(member -> {
                    if (logout) {
                        forgetMember(member);   // 로그아웃은 회원에서만 바로 뺀다 — 같은 브라우저라 접속자 수는 그대로
                    } else {
                        record(visitor, member.isEmpty() ? null : member);
                    }
                })
                .then(chain.filter(exchange));
    }

    /** 로그인 회원이면 u:{userId}, 아니면 "" */
    static Mono<String> memberKey() {
        return AuthenticatedUser.current().filter(AuthenticatedUser::linked).map(user -> "u:" + user.userId()).defaultIfEmpty("");
    }

    /**
     * 접속자 = 브라우저 하나(11/17). 화면이 브라우저마다 만든 무작위 방문자 ID(X-Gotgan-Visitor)로 센다 —
     * 로그인 전후가 같은 사람으로 세지고(로그인해도 접속자가 늘지 않음), 같은 와이파이(NAT)의 여러 명도 따로 세진다.
     * 헤더가 없거나 형식이 틀리면 요청 제한 키(ip:… / direct)
     */
    static String visitorKey(ServerWebExchange exchange) {
        String id = exchange.getRequest().getHeaders().getFirst(VISITOR_HEADER);
        return id != null && VISITOR_ID.matcher(id).matches() ? "v:" + id : RateLimitKeyResolver.anonymousKey(exchange.getRequest().getHeaders());
    }

    static boolean skip(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        return exchange.getRequest().getHeaders().containsHeader(LOADTEST_HEADER) || path.startsWith("/actuator");
    }

    /** 접속자(visitor)와 로그인 회원(member, 없으면 null)을 기록. 따로 보내고 기다리지 않는다 — Redis 가 느리거나 죽어도 요청은 그대로 */
    void record(String visitor, String member) {
        long now = clock.instant().getEpochSecond();
        String key = visitor + "|" + member;
        Long last = lastWritten.get(key);
        if (last != null && now - last < THROTTLE_SECONDS) {
            return;
        }
        if (lastWritten.size() > MAX_CACHED) {
            lastWritten.clear();
        }
        lastWritten.put(key, now);
        Mono<Boolean> write = redis.opsForZSet().add(VISITORS_KEY, visitor, now);
        if (member != null) {
            write = write.then(redis.opsForZSet().add(MEMBERS_KEY, member, now));
        }
        write.subscribe(ok -> { }, e -> log.debug("접속자 기록 실패 - {}", e.getMessage()));
    }

    /** 나감 — 접속자와 (로그인했으면) 회원에서 바로 빼고, 다시 오면 15초 제한 없이 기록되게 기록 시각도 지운다 */
    void forget(String visitor, String member) {
        lastWritten.keySet().removeIf(k -> k.startsWith(visitor + "|"));
        Mono<Long> remove = redis.opsForZSet().remove(VISITORS_KEY, visitor);
        if (member != null) {
            remove = remove.then(redis.opsForZSet().remove(MEMBERS_KEY, member));
        }
        remove.subscribe(n -> { }, e -> log.debug("접속자 삭제 실패 - {}", e.getMessage()));
    }

    /** 로그아웃 — 회원에서만 뺀다 */
    void forgetMember(String member) {
        if (member == null || member.isEmpty()) {
            return;
        }
        lastWritten.keySet().removeIf(k -> k.endsWith("|" + member));
        redis.opsForZSet().remove(MEMBERS_KEY, member).subscribe(n -> { }, e -> log.debug("회원 삭제 실패 - {}", e.getMessage()));
    }

    /** 요청 제한, 인증 뒤, 라우팅 전 */
    @Override
    public int getOrder() {
        return 0;
    }
}
