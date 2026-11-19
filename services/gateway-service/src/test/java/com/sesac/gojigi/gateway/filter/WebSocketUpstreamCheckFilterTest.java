package com.sesac.gojigi.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.RouteToRequestUrlFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class WebSocketUpstreamCheckFilterTest {

    private final WebSocketUpstreamCheckFilter filter = new WebSocketUpstreamCheckFilter();
    private final AtomicInteger calls = new AtomicInteger();
    private final GatewayFilterChain chain = exchange -> Mono.fromRunnable(calls::incrementAndGet);

    private static MockServerWebExchange exchange(int port, boolean upgrade) {
        MockServerHttpRequest.BaseBuilder<?> req = MockServerHttpRequest.get("/ws/chat");
        if (upgrade) {
            req.header("Upgrade", "websocket").header("Connection", "Upgrade");
        }
        MockServerWebExchange ex = MockServerWebExchange.from(req.build());
        ex.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, URI.create("http://127.0.0.1:" + port + "/ws/chat"));
        return ex;
    }

    /** 방금 열었다 닫은 포트 = 아무도 듣지 않는 포트 (파드 없음과 같은 연결 거부) */
    private static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    @DisplayName("업무 서비스에 붙을 수 있으면 WebSocket 라우팅으로 넘긴다")
    void reachable_passes() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            filter.filter(exchange(server.getLocalPort(), true), chain).block();
        }

        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("업무 서비스가 없으면 연결 예외 → 에러 처리기가 연결 실패(502)로 기록, 라우팅하지 않는다")
    void unreachable_failsAsConnectFailure() throws IOException {
        int port = closedPort();

        assertThatThrownBy(() -> filter.filter(exchange(port, true), chain).block())
                .hasRootCauseInstanceOf(ConnectException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("WebSocket 이 아닌 요청은 확인하지 않는다 (REST 는 라우팅 필터가 연결 실패를 직접 낸다)")
    void notWebSocket_skips() throws IOException {
        filter.filter(exchange(closedPort(), false), chain).block();

        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("요청 주소가 정해지기 전(라우트 없음)이면 그대로 넘긴다")
    void noTarget_skips() {
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.get("/ws/chat").header("Upgrade", "websocket").build());
        filter.filter(ex, chain).block();

        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("포트가 없으면 스킴 기본 포트")
    void defaultPort() {
        assertThat(WebSocketUpstreamCheckFilter.port(URI.create("http://chat-service/ws/chat"))).isEqualTo(80);
        assertThat(WebSocketUpstreamCheckFilter.port(URI.create("wss://chat-service/ws/chat"))).isEqualTo(443);
        assertThat(WebSocketUpstreamCheckFilter.port(URI.create("ws://chat-service:8089/ws/chat"))).isEqualTo(8089);
    }

    @Test
    @DisplayName("요청 주소 결정 뒤, WebSocket 라우팅 직전 순서")
    void order() {
        assertThat(filter.getOrder()).isGreaterThan(RouteToRequestUrlFilter.ROUTE_TO_URL_FILTER_ORDER).isLessThan(Ordered.LOWEST_PRECEDENCE - 1);
    }

}
