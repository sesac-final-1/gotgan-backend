package com.sesac.gojigi.admin.system.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Prometheus 즉시 쿼리 — 가짜 서버(JDK HttpServer)로 쿼리 인코딩 · vector 해석 · 실패면 빈 Optional */
class PrometheusClientTest {

    private HttpServer server;
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/query", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String raw = ex.getRequestURI().getRawQuery();
        queries.add(URLDecoder.decode(raw.substring("query=".length()), StandardCharsets.UTF_8));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private PrometheusClient client() {
        return new PrometheusClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("정상 — 쿼리를 그대로 보내고(중괄호 · 따옴표 인코딩) routeId → 값, routeId 없거나 NaN 인 시리즈는 뺀다")
    void byRoute_parses() {
        body = """
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"routeId":"order-service"},"value":[1762664592.1,"1.5"]},
                  {"metric":{"routeId":"user-auth"},"value":[1762664592.1,"0.25"]},
                  {"metric":{"routeId":"chat-service"},"value":[1762664592.1,"NaN"]},
                  {"metric":{},"value":[1762664592.1,"9"]}
                ]}}""";
        String promql = "sum by (routeId)(rate(spring_cloud_gateway_requests_seconds_count{httpStatusCode=~\"5..\"}[1m]))";

        assertThat(client().byRoute(promql)).hasValueSatisfying(values -> assertThat(values)
                .hasSize(2).containsEntry("order-service", 1.5).containsEntry("user-auth", 0.25));
        assertThat(queries).containsExactly(promql);
    }

    @Test
    @DisplayName("오류 응답(status error) · 4xx · 연결 실패 — 빈 Optional")
    void byRoute_failures() {
        body = "{\"status\":\"error\",\"errorType\":\"bad_data\"}";
        assertThat(client().byRoute("up")).isEmpty();

        status = 400;
        assertThat(client().byRoute("up")).isEmpty();

        PrometheusClient client = client();
        server.stop(0);
        assertThat(client.byRoute("up")).isEmpty();
    }
}
