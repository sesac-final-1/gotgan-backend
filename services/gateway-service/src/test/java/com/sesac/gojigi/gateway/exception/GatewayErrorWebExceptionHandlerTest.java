package com.sesac.gojigi.gateway.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.channel.ConnectTimeoutException;
import java.net.ConnectException;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** 예외 → 봉투 매핑의 모든 분기 (실제 라우팅 · 타임아웃 흐름은 config.GatewayHardeningTest) */
class GatewayErrorWebExceptionHandlerTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final GatewayErrorWebExceptionHandler handler =
            new GatewayErrorWebExceptionHandler(new GatewayErrorResponder(jsonMapper));

    private static MockServerWebExchange exchange() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders"));
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id("order-service").uri(URI.create("http://localhost:8084"))
                        .predicate(e -> true).build());
        return exchange;
    }

    /** 처리 후 상태 · 봉투 code · message */
    private Map<String, Object> handle(MockServerWebExchange exchange, Throwable ex) {
        handler.handle(exchange, ex).block();
        String body = exchange.getResponse().getBodyAsString().block();
        @SuppressWarnings("unchecked")
        Map<String, Object> json = jsonMapper.readValue(body, Map.class);
        json.put("status", exchange.getResponse().getStatusCode().value());
        return json;
    }

    @Test
    @DisplayName("연결 실패(원인 사슬에 ConnectException · Netty 연결 타임아웃) → 502 EXTERNAL_API_ERROR")
    void connectFailure_502() {
        Map<String, Object> refused = handle(exchange(), new RuntimeException("감쌈", new ConnectException("Connection refused")));
        Map<String, Object> timedOut = handle(exchange(), new ConnectTimeoutException("connection timed out"));

        assertThat(refused).containsEntry("status", 502).containsEntry("code", "EXTERNAL_API_ERROR")
                .containsEntry("message", "서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.").containsEntry("data", null);
        assertThat(timedOut).containsEntry("status", 502).containsEntry("code", "EXTERNAL_API_ERROR");
    }

    @Test
    @DisplayName("504 · 502 · 503 상태 예외 → 같은 상태 + EXTERNAL_API_ERROR (504 는 지연 문구)")
    void upstreamStatuses_externalApiError() {
        assertThat(handle(exchange(), new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Response took longer than timeout")))
                .containsEntry("status", 504).containsEntry("code", "EXTERNAL_API_ERROR")
                .containsEntry("message", "서비스 응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.");
        assertThat(handle(exchange(), new ResponseStatusException(HttpStatus.BAD_GATEWAY)))
                .containsEntry("status", 502).containsEntry("code", "EXTERNAL_API_ERROR");
        assertThat(handle(exchange(), new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)))
                .containsEntry("status", 503).containsEntry("code", "EXTERNAL_API_ERROR")
                .containsEntry("message", "서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }

    @Test
    @DisplayName("404 → NOT_FOUND · 405 → METHOD_NOT_ALLOWED · 그 밖 4xx → INVALID_INPUT (상태 그대로)")
    void clientErrors() {
        assertThat(handle(exchange(), new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .containsEntry("status", 404).containsEntry("code", "NOT_FOUND")
                .containsEntry("message", "요청한 리소스를 찾을 수 없습니다.");
        assertThat(handle(exchange(), new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED)))
                .containsEntry("status", 405).containsEntry("code", "METHOD_NOT_ALLOWED")
                .containsEntry("message", "지원하지 않는 요청 방식입니다.");
        assertThat(handle(exchange(), new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE)))
                .containsEntry("status", 413).containsEntry("code", "INVALID_INPUT")
                .containsEntry("message", "입력값이 올바르지 않습니다.");
    }

    @Test
    @DisplayName("그 밖 5xx 상태 예외 · 모르는 상태 코드 · 일반 예외 → GLOBAL_ERROR")
    void serverErrors_globalError() {
        assertThat(handle(exchange(), new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED)))
                .containsEntry("status", 501).containsEntry("code", "GLOBAL_ERROR")
                .containsEntry("message", "서버 오류가 발생했습니다.");
        assertThat(handle(exchange(), new ResponseStatusException(HttpStatusCode.valueOf(599))))
                .containsEntry("status", 500).containsEntry("code", "GLOBAL_ERROR");
        assertThat(handle(exchange(), new IllegalStateException("예상 못 한 오류")))
                .containsEntry("status", 500).containsEntry("code", "GLOBAL_ERROR");
    }

    @Test
    @DisplayName("라우트를 정하기 전 예외(라우트 속성 없음)도 봉투로 — 로그의 route 는 '-'")
    void noRouteAttribute_stillEnvelope() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/nothing"));

        assertThat(handle(exchange, new ResponseStatusException(HttpStatus.NOT_FOUND))).containsEntry("code", "NOT_FOUND");
    }

    @Test
    @DisplayName("이미 응답을 보내기 시작했으면 덮어쓰지 않고 예외를 그대로 넘긴다 (스트리밍 중 끊김 등)")
    void committedResponse_passesThrough() {
        MockServerWebExchange exchange = exchange();
        exchange.getResponse().setComplete().block();
        IllegalStateException error = new IllegalStateException("스트리밍 중 끊김");

        assertThatThrownBy(() -> handler.handle(exchange, error).block()).isSameAs(error);
    }

    @Test
    @DisplayName("연결 실패 판별 — 원인 사슬을 끝까지 본다, 없으면 false")
    void isConnectFailure() {
        assertThat(GatewayErrorWebExceptionHandler.isConnectFailure(
                new RuntimeException(new RuntimeException(new ConnectException("refused"))))).isTrue();
        assertThat(GatewayErrorWebExceptionHandler.isConnectFailure(new IllegalStateException("x"))).isFalse();
    }
}
