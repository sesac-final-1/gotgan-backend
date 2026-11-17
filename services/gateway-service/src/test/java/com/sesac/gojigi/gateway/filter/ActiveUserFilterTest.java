package com.sesac.gojigi.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveZSetOperations;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class ActiveUserFilterTest {

    private static final Instant NOW = Instant.parse("2026-11-17T05:00:00Z");
    private ReactiveZSetOperations<String, String> zset;
    private ActiveUserFilter filter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        zset = mock(ReactiveZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.add(anyString(), anyString(), anyDouble())).thenReturn(Mono.just(true));
        filter = new ActiveUserFilter(redis, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("비회원은 요청 제한 키(ip:… / direct)로 접속자에만 기록, 같은 사람은 15초에 한 번")
    void anonymous_recordedOncePerThrottle() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/products")
                .header(RateLimitKeyResolver.VERCEL_FORWARDED_FOR, "203.0.113.7").build());

        filter.filter(exchange, ex -> Mono.empty()).block();
        filter.filter(exchange, ex -> Mono.empty()).block();

        verify(zset, times(1)).add(ActiveUserFilter.VISITORS_KEY, "ip:203.0.113.7", NOW.getEpochSecond());
        verify(zset, never()).add(eq(ActiveUserFilter.MEMBERS_KEY), anyString(), anyDouble());
    }

    @Test
    @DisplayName("부하테스트 헤더와 액추에이터는 기록하지 않고 요청은 그대로 통과")
    void loadtestAndActuator_skipped() {
        boolean[] passed = {false, false};
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/products").header(ActiveUserFilter.LOADTEST_HEADER, "1").build()),
                ex -> { passed[0] = true; return Mono.empty(); }).block();
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health").build()),
                ex -> { passed[1] = true; return Mono.empty(); }).block();

        assertThat(passed).containsExactly(true, true);
        verify(zset, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    @DisplayName("회원은 접속자(방문자 ID)와 회원(u:id) 두 집합에 따로")
    void member_recordedInBoth() {
        filter.record("v:browser-1234", "u:42");

        verify(zset).add(ActiveUserFilter.VISITORS_KEY, "v:browser-1234", NOW.getEpochSecond());
        verify(zset).add(ActiveUserFilter.MEMBERS_KEY, "u:42", NOW.getEpochSecond());
    }

    @Test
    @DisplayName("방문자 ID 헤더가 있으면 그것으로 센다 — 로그인 전후 같은 브라우저는 접속자 1명, 형식이 틀리면 IP 키")
    void visitorKey_prefersBrowserId() {
        MockServerWebExchange withId = MockServerWebExchange.from(MockServerHttpRequest.get("/api/products")
                .header(ActiveUserFilter.VISITOR_HEADER, "a1b2c3d4-e5f6").header(RateLimitKeyResolver.VERCEL_FORWARDED_FOR, "203.0.113.7").build());
        MockServerWebExchange badId = MockServerWebExchange.from(MockServerHttpRequest.get("/api/products")
                .header(ActiveUserFilter.VISITOR_HEADER, "<script>").header(RateLimitKeyResolver.VERCEL_FORWARDED_FOR, "203.0.113.7").build());

        assertThat(ActiveUserFilter.visitorKey(withId)).isEqualTo("v:a1b2c3d4-e5f6");
        assertThat(ActiveUserFilter.visitorKey(badId)).isEqualTo("ip:203.0.113.7");

        filter.record("v:a1b2c3d4-e5f6", null);   // 로그인 전
        filter.record("v:a1b2c3d4-e5f6", "u:7");  // 로그인 후 — 접속자 키는 같다
        verify(zset, times(2)).add(ActiveUserFilter.VISITORS_KEY, "v:a1b2c3d4-e5f6", NOW.getEpochSecond());
        verify(zset).add(ActiveUserFilter.MEMBERS_KEY, "u:7", NOW.getEpochSecond());
    }

    @Test
    @DisplayName("나감 — 두 집합에서 지우고, 다시 들어오면 15초 제한 없이 바로 기록")
    void forget_removesAndAllowsImmediateRecord() {
        when(zset.remove(anyString(), org.mockito.ArgumentMatchers.<Object>any())).thenReturn(Mono.just(1L));
        filter.record("v:browser-1234", "u:7");

        filter.forget("v:browser-1234", "u:7");
        filter.record("v:browser-1234", "u:7");

        verify(zset).remove(ActiveUserFilter.VISITORS_KEY, "v:browser-1234");
        verify(zset).remove(ActiveUserFilter.MEMBERS_KEY, "u:7");
        verify(zset, times(2)).add(ActiveUserFilter.VISITORS_KEY, "v:browser-1234", NOW.getEpochSecond());
    }

    @Test
    @DisplayName("로그아웃 정리는 회원에서만 — 접속자는 그대로")
    void forgetMember_onlyMembers() {
        when(zset.remove(anyString(), org.mockito.ArgumentMatchers.<Object>any())).thenReturn(Mono.just(1L));

        filter.forgetMember("u:7");

        verify(zset).remove(ActiveUserFilter.MEMBERS_KEY, "u:7");
        verify(zset, never()).remove(eq(ActiveUserFilter.VISITORS_KEY), org.mockito.ArgumentMatchers.<Object>any());
    }

    @Test
    @DisplayName("로그아웃 요청은 기록하지 않는다(토큰이 있으면 그 회원을 바로 빼고, 없으면 아무것도 안 함)")
    void logout_notRecorded() {
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.post(ActiveUserFilter.LOGOUT_PATH)
                .header(RateLimitKeyResolver.VERCEL_FORWARDED_FOR, "203.0.113.7").build()), ex -> Mono.empty()).block();

        verify(zset, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    @DisplayName("역할별 회원 — 대표 역할(관리자 > 판매자 > 구매자) 집합에도 기록, 로그아웃하면 거기서도 뺀다")
    void role_recordedAndForgotten() {
        when(zset.remove(anyString(), org.mockito.ArgumentMatchers.<Object>any())).thenReturn(Mono.just(1L));
        assertThat(ActiveUserFilter.primaryRole(java.util.List.of("BUYER", "SELLER"))).isEqualTo("SELLER");
        assertThat(ActiveUserFilter.primaryRole(java.util.List.of("BUYER", "ADMIN"))).isEqualTo("ADMIN");
        assertThat(ActiveUserFilter.primaryRole(java.util.List.of("offline_access"))).isNull();

        filter.record("v:browser-1234", "u:9", "SELLER");
        filter.forgetMember("u:9", "SELLER");

        verify(zset).add(ActiveUserFilter.roleKey("SELLER"), "u:9", NOW.getEpochSecond());
        verify(zset).remove(ActiveUserFilter.roleKey("SELLER"), "u:9");
        verify(zset).remove(ActiveUserFilter.MEMBERS_KEY, "u:9");
    }
}
