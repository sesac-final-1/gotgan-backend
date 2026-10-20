package com.sesac.gojigi.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;

class UserHeaderFilterTest {

    private final UserHeaderFilter filter = new UserHeaderFilter();

    /** 체인에 넘어간 요청 헤더를 잡아 둔다 */
    private static HttpHeaders forwardedHeaders(MockServerWebExchange exchange, Map<String, Object> claims) {
        AtomicReference<HttpHeaders> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = e -> {
            forwarded.set(e.getRequest().getHeaders());
            return Mono.empty();
        };
        Mono<Void> result = new UserHeaderFilter().filter(exchange, chain);
        if (claims != null) {
            result = result.contextWrite(ReactiveSecurityContextHolder.withAuthentication(TestJwts.authentication(claims)));
        }
        result.block();
        return forwarded.get();
    }

    private static MockServerWebExchange spoofedRequest() {
        return MockServerWebExchange.from(MockServerHttpRequest.delete("/api/users/me")
                .header("X-User-Id", "999").header("X-User-Roles", "ADMIN").header("X-Store-Id", "999"));
    }

    @Test
    @DisplayName("판매자 토큰 — 클라이언트가 보낸 X-User-* 는 지우고 토큰의 user_id · 업무 역할 · store_id 로 다시 붙인다")
    void filter_seller_replacesSpoofedHeadersWithClaims() {
        HttpHeaders headers = forwardedHeaders(spoofedRequest(), TestJwts.seller());

        assertThat(headers.getFirst("X-User-Id")).isEqualTo("2");
        assertThat(headers.getFirst("X-User-Roles")).isEqualTo("BUYER,SELLER");   // Keycloak 기본 역할 제외
        assertThat(headers.getFirst("X-Store-Id")).isEqualTo("1");
    }

    @Test
    @DisplayName("구매자 토큰 — store_id 가 없으면 X-Store-Id 를 붙이지 않는다 (위조 값도 지워짐)")
    void filter_buyer_omitsStoreId() {
        HttpHeaders headers = forwardedHeaders(spoofedRequest(), TestJwts.buyer());

        assertThat(headers.getFirst("X-User-Id")).isEqualTo("4");
        assertThat(headers.getFirst("X-User-Roles")).isEqualTo("BUYER");
        assertThat(headers.containsHeader("X-Store-Id")).isFalse();
    }

    @Test
    @DisplayName("토큰 없음(공개 경로) · user_id 없는 토큰 — 위조 헤더를 지우기만 한다 → 업무 서비스가 401")
    void filter_anonymousOrUnlinked_stripsOnly() {
        HttpHeaders anonymous = forwardedHeaders(spoofedRequest(), null);
        HttpHeaders unlinked = forwardedHeaders(spoofedRequest(), Map.of("sub", "kc-x", "roles", java.util.List.of("BUYER")));

        for (HttpHeaders headers : new HttpHeaders[] { anonymous, unlinked }) {
            assertThat(headers.containsHeader("X-User-Id")).isFalse();
            assertThat(headers.containsHeader("X-User-Roles")).isFalse();
            assertThat(headers.containsHeader("X-Store-Id")).isFalse();
        }
    }

    @Test
    @DisplayName("차단 목록 확인(-2) 다음, 라우팅 전에 실행된다")
    void order_runsAfterBlocklistBeforeRouting() {
        assertThat(filter.getOrder()).isEqualTo(-1);
    }
}
