package com.sesac.gojigi.gateway.filter;

import com.sesac.gojigi.gateway.exception.GatewayErrorResponder;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 차단 목록 — 탈퇴한 회원의 남은 Access Token(최대 수명 30분)을 막는다.
 * user-service 가 탈퇴 때 Redis blocked:user:{userId} 를 토큰 수명만큼 저장하고, 여기서 서명 검증을 통과한 토큰의 user_id 로 확인한다.
 * Redis 장애면 확인 없이 통과(fail-open) — 보조 방어선이 전체 장애가 되지 않게.
 * 1차 방어선은 그대로: Keycloak 계정 삭제(새 로그인 · Refresh 불가) · user-service 의 deleted_at 확인.
 */
@Slf4j
@Component
public class BlockedUserFilter implements GlobalFilter, Ordered {

    static final String KEY_PREFIX = "blocked:user:";   // 컨벤션 2-2 Redis 키 · user-service BlockedUserRedisRepository 와 같은 키
    static final String CIRCUIT = "redisBlocklist";

    private final ReactiveStringRedisTemplate redis;
    private final CircuitBreaker circuitBreaker;
    private final GatewayErrorResponder responder;

    public BlockedUserFilter(ReactiveStringRedisTemplate redis, CircuitBreakerRegistry circuitBreakerRegistry,
            GatewayErrorResponder responder) {
        this.redis = redis;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT);
        this.responder = responder;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return AuthenticatedUser.current()
                .filter(AuthenticatedUser::linked)
                .map(user -> Optional.of(user.userId()))
                .defaultIfEmpty(Optional.empty())
                .flatMap(userId -> userId.isEmpty()
                        ? chain.filter(exchange)   // 공개 경로 · 토큰 없음 · user_id 없는 토큰 — 확인할 대상 없음
                        : isBlocked(userId.get()).flatMap(blocked -> blocked
                                ? reject(exchange, userId.get())
                                : chain.filter(exchange)));
    }

    private Mono<Boolean> isBlocked(String userId) {
        return Mono.defer(() -> redis.hasKey(KEY_PREFIX + userId))   // 서킷 OPEN 이면 Redis 명령 자체를 만들지 않는다
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(e -> {
                    if (e instanceof CallNotPermittedException) {
                        // 서킷 OPEN — 장애 원인은 OPEN 될 때까지의 warn 에 남아 있다. 요청마다 쌓이지 않게 debug
                        log.debug("차단 목록 서킷 OPEN — 확인 없이 통과 userId={}", userId);
                    } else {
                        log.warn("차단 목록 조회 실패 — 확인 없이 통과(fail-open), 탈퇴 회원의 남은 토큰이 통과할 수 있음 userId={}, cause={}",
                                userId, e.toString());
                    }
                    return Mono.just(false);
                });
    }

    private Mono<Void> reject(ServerWebExchange exchange, String userId) {
        log.warn("차단된 회원의 요청 거절 — 탈퇴 후 남은 토큰 userId={}, method={}, path={}",
                userId, exchange.getRequest().getMethod(), exchange.getRequest().getPath());
        return responder.write(exchange, HttpStatus.UNAUTHORIZED, GatewayErrorResponder.UNAUTHORIZED,
                "탈퇴했거나 이용이 제한된 계정입니다. 다시 로그인해 주세요.");
    }

    @Override
    public int getOrder() {
        return -2;   // 헤더 주입(UserHeaderFilter, -1) · 라우팅보다 먼저 — 차단된 요청은 업무 서비스로 보내지 않는다
    }
}
