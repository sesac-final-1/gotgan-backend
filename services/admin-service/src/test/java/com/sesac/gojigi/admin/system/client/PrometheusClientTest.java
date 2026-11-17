package com.sesac.gojigi.admin.system.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Prometheus 즉시 쿼리 — 가짜 서버(JDK HttpServer)로 쿼리 인코딩, vector 해석, 실패면 빈 Optional */
class PrometheusClientTest {

    private HttpServer server;
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/query", this::handle);
        server.createContext("/api/v1/query_range", this::handleRange);
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

    private void handleRange(HttpExchange ex) throws IOException {
        queries.add(URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
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
    @DisplayName("정상 — 쿼리를 그대로 보내고(중괄호, 따옴표 인코딩) routeId → 값, routeId 없거나 NaN 인 시리즈는 뺀다")
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
    @DisplayName("오류 응답(status error), 4xx, 연결 실패 — 빈 Optional")
    void byRoute_failures() {
        body = "{\"status\":\"error\",\"errorType\":\"bad_data\"}";
        assertThat(client().byRoute("up")).isEmpty();

        status = 400;
        assertThat(client().byRoute("up")).isEmpty();

        PrometheusClient client = client();
        server.stop(0);
        assertThat(client.byRoute("up")).isEmpty();
    }

    @Test
    @DisplayName("범위 쿼리 — start, end, step 을 초로 보내고 첫 시리즈의 [시각 ms, 값], NaN 은 뺀다")
    void range_parses() {
        body = """
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{},"values":[[1794805100,"40"],[1794805105.5,"NaN"],[1794805110,"42.5"]]}
                ]}}""";
        Instant start = Instant.ofEpochSecond(1794805000);

        assertThat(client().range("sum(k6_vus{testid=\"lt-1\"})", start, start.plusSeconds(300), Duration.ofSeconds(5)))
                .hasValueSatisfying(s -> assertThat(s).containsExactly(
                        new PrometheusClient.Sample(1794805100000L, 40), new PrometheusClient.Sample(1794805110000L, 42.5)));
        assertThat(queries).containsExactly("query=sum(k6_vus{testid=\"lt-1\"})&start=1794805000&end=1794805300&step=5");
    }

    @Test
    @DisplayName("범위 쿼리 — 결과 없음은 빈 목록, 오류 응답, 연결 실패는 빈 Optional")
    void range_emptyAndFailures() {
        Instant now = Instant.ofEpochSecond(1794805000);
        body = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}";
        assertThat(client().range("up", now, now, Duration.ofSeconds(5))).hasValue(List.of());

        body = "{\"status\":\"error\"}";
        assertThat(client().range("up", now, now, Duration.ofSeconds(5))).isEmpty();

        PrometheusClient client = client();
        server.stop(0);
        assertThat(client.range("up", now, now, Duration.ofSeconds(5))).isEmpty();
    }

    @Test
    @DisplayName("라벨별 범위 쿼리 — 시리즈마다 label 값 → [시각 ms, 값], 라벨 없으면 빈 문자열")
    void rangeBy_parses() {
        body = """
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"routeId":"order-service"},"values":[[100,"1.5"],[105,"NaN"]]},
                  {"metric":{},"values":[[100,"2"]]}
                ]}}""";
        Instant now = Instant.ofEpochSecond(100);

        assertThat(client().rangeBy("sum by (routeId)(x)", "routeId", now, now.plusSeconds(5), Duration.ofSeconds(5)))
                .hasValueSatisfying(m -> assertThat(m)
                        .containsEntry("order-service", List.of(new PrometheusClient.Sample(100_000, 1.5)))
                        .containsEntry("", List.of(new PrometheusClient.Sample(100_000, 2))));
    }

    @Test
    @DisplayName("벡터 쿼리 — 라벨 전체와 값, NaN 은 빼고 실패면 빈 Optional")
    void vector_parses() {
        body = """
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"app":"order-service","pool":"HikariPool-1"},"value":[1,"20"]},
                  {"metric":{"app":"chat-service"},"value":[1,"NaN"]}
                ]}}""";
        assertThat(client().vector("sum by (app)(hikaricp_connections_max)")).hasValueSatisfying(s -> assertThat(s)
                .containsExactly(new PrometheusClient.Series(java.util.Map.of("app", "order-service", "pool", "HikariPool-1"), 20)));

        body = "{\"status\":\"error\"}";
        assertThat(client().vector("up")).isEmpty();
        assertThat(client().rangeBy("up", "app", Instant.EPOCH, Instant.EPOCH, Duration.ofSeconds(5))).isEmpty();
    }

    @Test
    @DisplayName("장애 리포트 — 지난 시각 기준 벡터(time 전달), 시리즈마다 라벨 전체, 점 목록(matrix), 실패면 빈 Optional")
    void vectorAt_and_matrix() {
        body = """
                {"status":"success","data":{"resultType":"vector","result":[{"metric":{"pod":"product-service-a"},"value":[1,"1794881208"]}]}}""";
        assertThat(client().vector("last_over_time(kube_pod_created[1h])", Instant.ofEpochSecond(1_794_884_000)))
                .hasValueSatisfying(s -> assertThat(s).containsExactly(
                        new PrometheusClient.Series(java.util.Map.of("pod", "product-service-a"), 1_794_881_208)));
        assertThat(queries.get(0)).contains("last_over_time(kube_pod_created[1h])", "time=1794884000");

        body = """
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"routeId":"product-service","httpStatusCode":"500"},"values":[[60,"431.2"],[120,"NaN"]]}]}}""";
        assertThat(client().matrix("sum by (routeId)(x)", Instant.ofEpochSecond(60), Instant.ofEpochSecond(120), Duration.ofMinutes(1)))
                .hasValueSatisfying(s -> assertThat(s).containsExactly(new PrometheusClient.RangeSeries(
                        java.util.Map.of("routeId", "product-service", "httpStatusCode", "500"), List.of(new PrometheusClient.Sample(60_000, 431.2)))));

        body = "{\"status\":\"error\"}";
        assertThat(client().matrix("up", Instant.EPOCH, Instant.EPOCH, Duration.ofMinutes(1))).isEmpty();
        assertThat(client().vector("up", Instant.EPOCH)).isEmpty();
    }
}
