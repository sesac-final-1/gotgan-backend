package com.sesac.gojigi.admin.system.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Zipkin · Loki 클라이언트 — 가짜 서버(JDK HttpServer)로 쿼리 · 응답 해석 · 실패면 빈 목록 확인 */
class MonitorClientsTest {

    private HttpServer server;
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "[]";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String raw = ex.getRequestURI().getRawQuery();
        queries.add(ex.getRequestURI().getPath() + "?" + (raw == null ? "" : URLDecoder.decode(raw, StandardCharsets.UTF_8)));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    @DisplayName("Zipkin 최근 트레이스 — 게이트웨이 · 시각 · 조건을 보내고 트레이스 목록, 실패면 빈 목록")
    void zipkin_recentTraces() {
        body = "[[{\"traceId\":\"a1\"}],[{\"traceId\":\"b2\"}]]";
        ZipkinClient client = new ZipkinClient(RestClient.builder(), url());

        assertThat(client.recentTraces("outcome=SERVER_ERROR", 1000, 60000, 40)).hasSize(2);
        assertThat(queries.get(0)).startsWith("/api/v2/traces?").contains("serviceName=gateway-service", "endTs=1000", "lookback=60000",
                "limit=40", "annotationQuery=outcome=SERVER_ERROR");
        assertThat(client.recentTraces(null, 1000, 60000, 40)).hasSize(2);
        assertThat(queries.get(1)).doesNotContain("annotationQuery");

        status = 500;
        assertThat(client.recentTraces(null, 1000, 60000, 40)).isEmpty();
    }

    @Test
    @DisplayName("Zipkin 트레이스 하나 — 구간 목록, 없으면(404) 빈 목록")
    void zipkin_trace() {
        body = "[{\"id\":\"s1\"},{\"id\":\"s2\"}]";
        ZipkinClient client = new ZipkinClient(RestClient.builder(), url());
        assertThat(client.trace("abc123")).hasSize(2);
        assertThat(queries.get(0)).startsWith("/api/v2/trace/abc123");

        status = 404;
        assertThat(client.trace("abc123")).isEmpty();
    }

    @Test
    @DisplayName("Loki 에러 로그 — WARN · ERROR 쿼리, JSON 본문의 message · traceId, 최신순 · limit, 실패면 빈 목록")
    void loki_errors() {
        body = """
                {"status":"success","data":{"resultType":"streams","result":[
                  {"stream":{"app":"order-service","level":"WARN"},"values":[
                    ["2000000000","{\\"message\\":\\"business exception - code=CART_ITEM_NOT_FOUND\\",\\"traceId\\":\\"t1\\"}"],
                    ["1000000000","plain text line"]]},
                  {"stream":{"app":"user-service","level":"ERROR"},"values":[["3000000000","{\\"message\\":\\"boom\\"}"]]}
                ]}}""";
        LokiClient client = new LokiClient(RestClient.builder(), url());

        List<LokiClient.LogLine> lines = client.errors(Instant.ofEpochMilli(0), Instant.ofEpochMilli(5000), 2, null);

        assertThat(queries.get(0)).startsWith("/loki/api/v1/query_range?").contains(LokiClient.ERROR_QUERY, "direction=backward", "limit=2");
        assertThat(lines).containsExactly(
                new LokiClient.LogLine(3000, "user-service", "ERROR", "boom", null),
                new LokiClient.LogLine(2000, "order-service", "WARN", "business exception - code=CART_ITEM_NOT_FOUND", "t1"));
        assertThat(LokiClient.line("a", "WARN", 1, "plain text line").message()).isEqualTo("plain text line");

        client.errors(Instant.EPOCH, Instant.EPOCH, 2, "order-service");
        assertThat(queries.get(1)).contains("level=~\"WARN|ERROR\",app=\"order-service\"}");

        status = 500;
        assertThat(client.errors(Instant.EPOCH, Instant.EPOCH, 2, null)).isEmpty();
    }
}
