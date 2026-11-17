package com.sesac.gojigi.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sesac.gojigi.admin.system.client.LokiClient;
import com.sesac.gojigi.admin.system.client.PrometheusClient;
import com.sesac.gojigi.admin.system.client.PrometheusClient.Sample;
import com.sesac.gojigi.admin.system.client.PrometheusClient.Series;
import com.sesac.gojigi.admin.system.client.ZipkinClient;
import com.sesac.gojigi.admin.system.dto.response.MonitorResponses;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실시간 모니터링 — Prometheus · Zipkin · Loki 는 가짜. 라우트 → 서비스 합산 · 평균 · 에러율 · 호출 해석 · 장애 합치기 · 트레이스 · 분석 */
class MonitorServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-16T07:00:00Z");

    private final PrometheusClient prometheus = mock(PrometheusClient.class);
    private final ZipkinClient zipkin = mock(ZipkinClient.class);
    private final LokiClient loki = mock(LokiClient.class);
    private final MonitorService service = new MonitorService(prometheus, zipkin, loki, Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));

    private static List<JsonNode> traces(String json) {
        List<JsonNode> list = new ArrayList<>();
        JSON.readTree(json).forEach(list::add);
        return list;
    }

    /** 게이트웨이 첫 구간 + 라우트 태그가 있는 클라이언트 구간 */
    private static String trace(String id, long tsMicros, String path, String status, String route) {
        return """
                [{"traceId":"%s","id":"r","kind":"SERVER","timestamp":%d,"duration":12500,
                  "localEndpoint":{"serviceName":"gateway-service"},"tags":{"http.url":"%s","method":"GET","status":"%s"}},
                 {"traceId":"%s","id":"c","parentId":"r","kind":"CLIENT","timestamp":%d,"duration":9000,
                  "localEndpoint":{"serviceName":"gateway-service"},"tags":{"spring.cloud.gateway.route.id":"%s","http.status_code":"%s"}}]"""
                .formatted(id, tsMicros, path, status, id, tsMicros + 100, route, status);
    }

    @Test
    @DisplayName("현황 — 라우트를 서비스로 합치고(user-auth + user-service), 평균 ms · 에러율 계산, 총합, DB 연결 풀")
    void overview() {
        when(prometheus.rangeBy(eq(MonitorService.COUNT_BY_ROUTE), any(), any(), any(), any())).thenReturn(Optional.of(Map.of(
                "user-auth", List.of(new Sample(1000, 2), new Sample(6000, 0)),
                "user-service", List.of(new Sample(1000, 2)),
                "product-service", List.of(new Sample(1000, 10)),
                "keycloak", List.of(new Sample(1000, 99)))));
        when(prometheus.rangeBy(eq(MonitorService.SUM_BY_ROUTE), any(), any(), any(), any())).thenReturn(Optional.of(Map.of(
                "user-auth", List.of(new Sample(1000, 0.2)), "user-service", List.of(new Sample(1000, 0.2)),
                "product-service", List.of(new Sample(1000, 0.5)))));
        when(prometheus.rangeBy(eq(MonitorService.ERRORS_BY_ROUTE), any(), any(), any(), any())).thenReturn(Optional.of(Map.of(
                "product-service", List.of(new Sample(1000, 1)))));
        when(prometheus.vector(anyString())).thenReturn(Optional.of(List.of()));
        when(prometheus.vector("sum by (app)(hikaricp_connections_max)"))
                .thenReturn(Optional.of(List.of(new Series(Map.of("app", "user-service"), 20))));
        when(prometheus.vector("sum by (app)(hikaricp_connections_active)"))
                .thenReturn(Optional.of(List.of(new Series(Map.of("app", "user-service"), 3))));

        MonitorResponses.Overview overview = service.overview();

        MonitorResponses.ServiceSeries user = overview.services().stream().filter(s -> s.name().equals("user-service")).findFirst().orElseThrow();
        assertThat(user.series().rps()).containsExactly(new Sample(1000, 4), new Sample(6000, 0));
        assertThat(user.series().avgMs()).containsExactly(new Sample(1000, 100), new Sample(6000, 0));
        assertThat(user.db()).isEqualTo(new MonitorResponses.Db(3, 0, 20));
        MonitorResponses.ServiceSeries product = overview.services().stream().filter(s -> s.name().equals("product-service")).findFirst().orElseThrow();
        assertThat(product.series().errorRate()).containsExactly(new Sample(1000, 0.1));
        assertThat(product.series().avgMs()).containsExactly(new Sample(1000, 50));
        assertThat(product.db()).isNull();
        assertThat(overview.services()).extracting(MonitorResponses.ServiceSeries::name).doesNotContain("keycloak", "mysql", "gateway-service");
        assertThat(overview.total().rps()).contains(new Sample(1000, 14));                      // keycloak 99 는 빠짐
        assertThat(overview.total().errorRate()).contains(new Sample(1000, 0.0714));
    }

    @Test
    @DisplayName("현황 — Prometheus 실패면 빈 그래프")
    void overview_empty() {
        when(prometheus.rangeBy(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(prometheus.vector(anyString())).thenReturn(Optional.empty());

        MonitorResponses.Overview overview = service.overview();

        assertThat(overview.total().rps()).isEmpty();
        assertThat(overview.services()).allSatisfy(s -> assertThat(s.series().rps()).isEmpty());
    }

    @Test
    @DisplayName("호출 — 게이트웨이 첫 구간으로 한 줄(경로 · 상태 · ms · 라우트 → 서비스), 최신순, 관리자 시스템 폴링 · 액추에이터는 뺀다")
    void calls() {
        when(zipkin.recentTraces(isNull(), anyLong(), anyLong(), anyInt())).thenReturn(traces("[" + String.join(",",
                trace("t1", 1_000_000, "/api/products/101", "200", "product-service"),
                trace("t2", 3_000_000, "/api/auth/login", "502", "user-auth"),
                trace("t3", 2_000_000, "/api/admin/system/status", "200", "admin-service"),
                trace("t5", 4_000_000, "/actuator/prometheus", "200", "gateway-service"),
                "[{\"traceId\":\"t4\",\"id\":\"x\",\"parentId\":\"y\",\"kind\":\"CLIENT\",\"localEndpoint\":{\"serviceName\":\"order-service\"},\"tags\":{}}]") + "]"));

        List<MonitorResponses.Call> calls = service.calls(null);

        assertThat(calls).containsExactly(
                new MonitorResponses.Call("t2", 3000, "GET", "/api/auth/login", "user-service", 502, 12.5),
                new MonitorResponses.Call("t1", 1000, "GET", "/api/products/101", "product-service", 200, 12.5));
    }

    @Test
    @DisplayName("장애 — 게이트웨이 5xx 응답과 WARN · ERROR 로그를 최신순으로 합친다")
    void errors() {
        when(zipkin.recentTraces(eq("outcome=SERVER_ERROR"), anyLong(), anyLong(), anyInt()))
                .thenReturn(traces("[" + trace("t9", 5_000_000, "/api/reviews", "502", "review-service") + "]"));
        when(loki.errors(any(), any(), anyInt(), isNull())).thenReturn(List.of(
                new LokiClient.LogLine(7000, "order-service", "WARN", "business exception - code=EXTERNAL_API_ERROR", "t8"),
                new LokiClient.LogLine(1000, "user-service", "ERROR", "boom", null)));

        List<MonitorResponses.ErrorItem> errors = service.errors(null);

        assertThat(errors).containsExactly(
                new MonitorResponses.ErrorItem("LOG", 7000, "order-service", "WARN", "business exception - code=EXTERNAL_API_ERROR", null, "t8"),
                new MonitorResponses.ErrorItem("RESPONSE", 5000, "review-service", "ERROR", "GET /api/reviews", 502, "t9"),
                new MonitorResponses.ErrorItem("LOG", 1000, "user-service", "ERROR", "boom", null, null));
    }

    @Test
    @DisplayName("트레이스 — 시작 시각순, 시작 오프셋 · ms, 경로 붙인 이름, 5xx · error 태그는 장애, 없으면 빈 구간")
    void trace() {
        when(zipkin.trace("t2")).thenReturn(traces("""
                [{"id":"c","parentId":"r","kind":"CLIENT","timestamp":1002000,"duration":4000,"name":"http get",
                  "localEndpoint":{"serviceName":"gateway-service"},"tags":{"http.status_code":"502","error":"Connection refused"}},
                 {"id":"r","kind":"SERVER","timestamp":1000000,"duration":10000,"name":"http get",
                  "localEndpoint":{"serviceName":"gateway-service"},"tags":{"http.url":"/api/reviews","status":"502"}}]"""));

        MonitorResponses.Trace trace = service.trace("t2");

        assertThat(trace.durationMs()).isEqualTo(10.0);
        assertThat(trace.spans()).containsExactly(
                new MonitorResponses.Span("r", null, "gateway-service", "http get /api/reviews", "SERVER", 0, 10, 502, true),
                new MonitorResponses.Span("c", "r", "gateway-service", "http get", "CLIENT", 2, 4, 502, true));

        when(zipkin.trace("none")).thenReturn(List.of());
        assertThat(service.trace("none").spans()).isEmpty();
    }

    @Test
    @DisplayName("분석 — 게이트웨이 · 서비스 5xx 를 합쳐 TOP 5, 느린 API(ms), 상태 코드 분포(0 건 제외)")
    void summary() {
        when(prometheus.vector(MonitorService.TOP_GATEWAY_ERRORS)).thenReturn(Optional.of(List.of(
                new Series(Map.of("routeId", "review-service", "httpStatusCode", "502"), 40.4))));
        when(prometheus.vector(MonitorService.TOP_APP_ERRORS)).thenReturn(Optional.of(List.of(
                new Series(Map.of("app", "order-service", "method", "POST", "uri", "/api/carts/items", "status", "502"), 3),
                new Series(Map.of("app", "user-service", "method", "GET", "uri", "/api/users/me", "status", "500"), 0.2))));
        when(prometheus.vector(MonitorService.SLOWEST)).thenReturn(Optional.of(List.of(
                new Series(Map.of("app", "order-service", "method", "POST", "uri", "/api/orders"), 0.12),
                new Series(Map.of("app", "product-service", "method", "GET", "uri", "/api/products"), 0.3))));
        when(prometheus.vector(MonitorService.STATUSES)).thenReturn(Optional.of(List.of(
                new Series(Map.of("httpStatusCode", "502"), 40), new Series(Map.of("httpStatusCode", "200"), 900.6),
                new Series(Map.of("httpStatusCode", "401"), 0.1))));

        MonitorResponses.Summary summary = service.summary();

        assertThat(summary.topErrors()).containsExactly(
                new MonitorResponses.ApiCount("review-service", "게이트웨이 라우트 review-service", "502", 40),
                new MonitorResponses.ApiCount("order-service", "POST /api/carts/items", "502", 3));
        assertThat(summary.slowest()).containsExactly(
                new MonitorResponses.ApiLatency("product-service", "GET /api/products", 300),
                new MonitorResponses.ApiLatency("order-service", "POST /api/orders", 120));
        assertThat(summary.statuses()).containsExactly(new MonitorResponses.StatusCount("200", 901), new MonitorResponses.StatusCount("502", 40));
    }

    @Test
    @DisplayName("서비스 필터 — 그 서비스의 라우트마다 Zipkin 을 조회해 합치고(중복 traceId 하나), 다른 서비스 줄은 뺀다")
    void calls_byService() {
        when(zipkin.recentTraces(eq("spring.cloud.gateway.route.id=user-auth"), anyLong(), anyLong(), anyInt()))
                .thenReturn(traces("[" + trace("u1", 2_000_000, "/api/auth/login", "200", "user-auth") + "]"));
        when(zipkin.recentTraces(eq("spring.cloud.gateway.route.id=user-service"), anyLong(), anyLong(), anyInt()))
                .thenReturn(traces("[" + trace("u2", 3_000_000, "/api/users/me", "200", "user-service") + ","
                        + trace("u1", 2_000_000, "/api/auth/login", "200", "user-auth") + "]"));
        when(zipkin.recentTraces(eq("spring.cloud.gateway.route.id=user-auth-strict"), anyLong(), anyLong(), anyInt()))
                .thenReturn(traces("[" + trace("p1", 1_000_000, "/api/products", "200", "product-service") + "]"));

        assertThat(service.calls("user-service")).extracting(MonitorResponses.Call::traceId).containsExactly("u2", "u1");
    }

    @Test
    @DisplayName("서비스 필터 — 장애는 5xx 조건과 라우트를 함께, 로그는 그 서비스만")
    void errors_byService() {
        when(zipkin.recentTraces(eq("outcome=SERVER_ERROR and spring.cloud.gateway.route.id=review-service"), anyLong(), anyLong(), anyInt()))
                .thenReturn(traces("[" + trace("r1", 5_000_000, "/api/reviews", "502", "review-service") + "]"));
        when(loki.errors(any(), any(), anyInt(), eq("review-service")))
                .thenReturn(List.of(new LokiClient.LogLine(6000, "review-service", "ERROR", "boom", "r2")));

        assertThat(service.errors("review-service")).extracting(MonitorResponses.ErrorItem::traceId).containsExactly("r2", "r1");
        assertThat(MonitorService.isFilterable("review-service")).isTrue();
        assertThat(MonitorService.isFilterable(null)).isTrue();
        assertThat(MonitorService.isFilterable("mysql")).isFalse();
    }
}
