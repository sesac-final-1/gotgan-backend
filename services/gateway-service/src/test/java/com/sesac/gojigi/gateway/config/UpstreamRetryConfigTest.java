package com.sesac.gojigi.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.RouteToRequestUrlFilter;
import org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

class UpstreamRetryConfigTest {

    private final GlobalFilter filter = new UpstreamRetryConfig().upstreamRetryFilter(new RetryGatewayFilterFactory());

    /** 처음 failures 번은 error 로 실패하고 그 뒤 성공하는 체인 — 실제 체인(DefaultGatewayFilterChain)처럼 구독할 때마다 다시 실행 */
    private static GatewayFilterChain failing(AtomicInteger calls, int failures, Throwable error) {
        return exchange -> Mono.defer(() -> calls.incrementAndGet() <= failures ? Mono.error(error) : Mono.empty());
    }

    @Test
    @DisplayName("GET 은 연결 끊김, 연결 실패 때 최대 2번 다시 보낸다")
    void get_retriesOnIoFailure() {
        AtomicInteger calls = new AtomicInteger();
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/products").build()),
                failing(calls, 2, PrematureCloseException.TEST_EXCEPTION)).block();

        assertThat(calls).hasValue(3);
    }

    @Test
    @DisplayName("3번 모두 실패하면 원래 예외를 넘긴다 (에러 처리기가 502)")
    void get_givesUpAfterRetries() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/products").build()),
                failing(calls, 9, new IOException("refused"))).block()).hasRootCauseInstanceOf(IOException.class);

        assertThat(calls).hasValue(1 + UpstreamRetryConfig.RETRIES);
    }

    @Test
    @DisplayName("POST 는 중복 처리 위험이 있어 재시도하지 않는다")
    void post_noRetry() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> filter.filter(MockServerWebExchange.from(MockServerHttpRequest.post("/api/carts/items").build()),
                failing(calls, 9, new IOException("refused"))).block()).isNotNull();

        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("IOException 이 아닌 예외는 재시도하지 않는다")
    void otherException_noRetry() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/products").build()),
                failing(calls, 9, new IllegalStateException("x"))).block()).isInstanceOf(IllegalStateException.class);

        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("요청 제한(라우트 필터) 뒤, 요청 주소 결정 직전 순서")
    void order() {
        assertThat(((Ordered) filter).getOrder()).isEqualTo(RouteToRequestUrlFilter.ROUTE_TO_URL_FILTER_ORDER - 1).isGreaterThan(1000);
    }
}
