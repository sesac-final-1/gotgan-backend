package com.sesac.gojigi.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.gateway.exception.GatewayErrorResponder;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

class BlockedUserFilterTest {

    private ReactiveStringRedisTemplate redis;
    private CircuitBreakerRegistry circuitBreakerRegistry;
    private BlockedUserFilter filter;
    private AtomicBoolean forwarded;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        redis = mock(ReactiveStringRedisTemplate.class);
        circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
        filter = new BlockedUserFilter(redis, circuitBreakerRegistry, new GatewayErrorResponder(JsonMapper.builder().build()));
        forwarded = new AtomicBoolean(false);
        chain = exchange -> {
            forwarded.set(true);
            return Mono.empty();
        };
    }

    private MockServerWebExchange run(Map<String, Object> claims) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders"));
        Mono<Void> result = filter.filter(exchange, chain);
        if (claims != null) {
            result = result.contextWrite(ReactiveSecurityContextHolder.withAuthentication(TestJwts.authentication(claims)));
        }
        result.block();
        return exchange;
    }

    @Test
    @DisplayName("차단 목록에 있는 회원(탈퇴 후 남은 토큰)은 401 — 업무 서비스로 보내지 않는다")
    void filter_blockedUser_rejects401() {
        given(redis.hasKey("blocked:user:4")).willReturn(Mono.just(true));

        MockServerWebExchange exchange = run(TestJwts.buyer());

        assertThat(forwarded).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"code\":\"UNAUTHORIZED\"").contains("탈퇴했거나 이용이 제한된 계정입니다");
    }

    @Test
    @DisplayName("차단 목록에 없으면 통과")
    void filter_notBlocked_forwards() {
        given(redis.hasKey("blocked:user:4")).willReturn(Mono.just(false));

        run(TestJwts.buyer());

        assertThat(forwarded).isTrue();
    }

    @Test
    @DisplayName("Redis 장애면 확인 없이 통과(fail-open) — 보조 방어선이 전체 장애가 되지 않게")
    void filter_redisDown_failsOpen() {
        given(redis.hasKey("blocked:user:4")).willReturn(Mono.error(new RedisConnectionFailureException("down")));

        run(TestJwts.buyer());

        assertThat(forwarded).isTrue();
    }

    @Test
    @DisplayName("서킷 OPEN 이면 Redis 를 부르지 않고 통과")
    void filter_circuitOpen_skipsRedis() {
        circuitBreakerRegistry.circuitBreaker(BlockedUserFilter.CIRCUIT).transitionToOpenState();

        run(TestJwts.buyer());

        assertThat(forwarded).isTrue();
        verify(redis, never()).hasKey(anyString());
    }

    @Test
    @DisplayName("토큰 없음(공개 경로) · user_id 없는 토큰은 확인하지 않고 통과")
    void filter_anonymousOrUnlinked_skipsCheck() {
        run(null);
        assertThat(forwarded).isTrue();

        forwarded.set(false);
        run(Map.of("sub", "kc-x", "roles", java.util.List.of("BUYER")));
        assertThat(forwarded).isTrue();

        verify(redis, never()).hasKey(anyString());
    }

    @Test
    @DisplayName("헤더 주입(-1) · 라우팅보다 먼저 실행된다")
    void order_runsBeforeHeaderInjection() {
        assertThat(filter.getOrder()).isEqualTo(-2);
    }
}
