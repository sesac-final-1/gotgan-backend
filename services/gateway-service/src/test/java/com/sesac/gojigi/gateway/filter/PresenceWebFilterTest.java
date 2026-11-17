package com.sesac.gojigi.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class PresenceWebFilterTest {

    private final ActiveUserFilter activeUsers = mock(ActiveUserFilter.class);
    private final PresenceWebFilter filter = new PresenceWebFilter(activeUsers);

    private static MockServerWebExchange post(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path).header(RateLimitKeyResolver.VERCEL_FORWARDED_FOR, "203.0.113.9").build());
    }

    @Test
    @DisplayName("ping 은 접속자로 기록하고 업무 서비스로 보내지 않고 204")
    void ping() {
        boolean[] passed = {false};
        MockServerWebExchange exchange = post(PresenceWebFilter.PING);

        filter.filter(exchange, ex -> { passed[0] = true; return Mono.empty(); }).block();

        verify(activeUsers).record("ip:203.0.113.9", null);
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(passed[0]).isFalse();
    }

    @Test
    @DisplayName("leave 는 접속자에서 바로 뺀다")
    void leave() {
        filter.filter(post(PresenceWebFilter.LEAVE), ex -> Mono.empty()).block();

        verify(activeUsers).forget("ip:203.0.113.9", null);
    }

    @Test
    @DisplayName("다른 경로나 GET 은 그대로 다음 필터로")
    void otherRequests_passThrough() {
        boolean[] passed = {false, false};
        filter.filter(post("/api/products"), ex -> { passed[0] = true; return Mono.empty(); }).block();
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get(PresenceWebFilter.PING).build()),
                ex -> { passed[1] = true; return Mono.empty(); }).block();

        assertThat(passed).containsExactly(true, true);
        verify(activeUsers, never()).record(anyString(), org.mockito.ArgumentMatchers.any());
    }
}
