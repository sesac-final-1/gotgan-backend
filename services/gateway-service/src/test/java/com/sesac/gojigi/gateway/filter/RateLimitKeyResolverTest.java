package com.sesac.gojigi.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;

/** 요청 제한 키 — 로그인은 userId, 비로그인은 Vercel 이 넣은 IP, 그 밖에는 공용 키 direct (X-Forwarded-For 는 보지 않음) */
class RateLimitKeyResolverTest {

    private final RateLimitKeyResolver resolver = new RateLimitKeyResolver();

    private String resolve(MockServerHttpRequest.BaseBuilder<?> request) {
        return resolver.resolve(MockServerWebExchange.from(request)).block();
    }

    private String resolveAs(Map<String, Object> claims, MockServerHttpRequest.BaseBuilder<?> request) {
        return resolver.resolve(MockServerWebExchange.from(request))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(TestJwts.authentication(claims)))
                .block();
    }

    private static HttpHeaders vercelHeader(String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(RateLimitKeyResolver.VERCEL_FORWARDED_FOR, value);
        return headers;
    }

    @Test
    @DisplayName("로그인 요청은 토큰의 user_id — Vercel IP 헤더가 있어도 사용자 기준")
    void loggedIn_usesUserId() {
        String key = resolveAs(TestJwts.buyer(), MockServerHttpRequest.get("/api/orders")
                .header(RateLimitKeyResolver.VERCEL_FORWARDED_FOR, "210.205.48.210"));

        assertThat(key).isEqualTo("user:4");
    }

    @Test
    @DisplayName("user_id 없는 토큰(회원 미연결)은 비로그인과 같게 — 헤더 없으면 direct")
    void tokenWithoutUserId_isAnonymous() {
        String key = resolveAs(Map.of("sub", "kc-9", "roles", List.of("BUYER")), MockServerHttpRequest.get("/api/orders"));

        assertThat(key).isEqualTo(RateLimitKeyResolver.DIRECT);
    }

    @Test
    @DisplayName("비로그인 + Vercel 경유 — X-Vercel-Forwarded-For 의 IP (여러 개면 첫 번째, IPv6 도)")
    void anonymousViaVercel_usesVercelIp() {
        assertThat(resolve(MockServerHttpRequest.post("/api/auth/login")
                .header(RateLimitKeyResolver.VERCEL_FORWARDED_FOR, "210.205.48.210"))).isEqualTo("ip:210.205.48.210");
        assertThat(RateLimitKeyResolver.anonymousKey(vercelHeader(" 210.205.48.210 , 10.0.0.1"))).isEqualTo("ip:210.205.48.210");
        assertThat(RateLimitKeyResolver.anonymousKey(vercelHeader("2001:db8::1"))).isEqualTo("ip:2001:db8::1");
    }

    @Test
    @DisplayName("X-Forwarded-For 는 보지 않는다 — Vercel 을 거쳐도 클라이언트가 보낸 값이 그대로 오므로 (직접 호출은 direct)")
    void forwardedFor_isIgnored() {
        assertThat(resolve(MockServerHttpRequest.post("/api/auth/login")
                .header("X-Forwarded-For", "1.2.3.4"))).isEqualTo(RateLimitKeyResolver.DIRECT);
        assertThat(resolve(MockServerHttpRequest.post("/api/auth/login")
                .header("X-Forwarded-For", "1.2.3.4")
                .header(RateLimitKeyResolver.VERCEL_FORWARDED_FOR, "210.205.48.210"))).isEqualTo("ip:210.205.48.210");
    }

    @Test
    @DisplayName("IP 모양이 아니거나 너무 긴 값은 direct — 지어낸 값으로 Redis 키를 늘리지 못하게")
    void invalidVercelIp_fallsBackToDirect() {
        assertThat(RateLimitKeyResolver.anonymousKey(vercelHeader(""))).isEqualTo(RateLimitKeyResolver.DIRECT);
        assertThat(RateLimitKeyResolver.anonymousKey(vercelHeader("evil}key"))).isEqualTo(RateLimitKeyResolver.DIRECT);
        assertThat(RateLimitKeyResolver.anonymousKey(vercelHeader("1".repeat(46)))).isEqualTo(RateLimitKeyResolver.DIRECT);
    }

    @Test
    @DisplayName("보안 컨텍스트가 없어도(공개 경로) 빈 키를 내지 않는다 — 빈 키면 내장 필터가 403 으로 막으므로")
    void noSecurityContext_neverEmpty() {
        String key = resolver.resolve(MockServerWebExchange.from(MockServerHttpRequest.get("/api/products")))
                .switchIfEmpty(Mono.just("EMPTY")).block();

        assertThat(key).isEqualTo(RateLimitKeyResolver.DIRECT);
    }
}
