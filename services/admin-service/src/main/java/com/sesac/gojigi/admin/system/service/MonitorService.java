package com.sesac.gojigi.admin.system.service;

import com.sesac.gojigi.admin.system.client.LokiClient;
import com.sesac.gojigi.admin.system.client.PrometheusClient;
import com.sesac.gojigi.admin.system.client.PrometheusClient.Sample;
import com.sesac.gojigi.admin.system.client.PrometheusClient.Series;
import com.sesac.gojigi.admin.system.client.ZipkinClient;
import com.sesac.gojigi.admin.system.dto.response.MonitorResponses;
import com.sesac.gojigi.admin.system.dto.response.MonitorResponses.ApiCount;
import com.sesac.gojigi.admin.system.dto.response.MonitorResponses.ApiLatency;
import com.sesac.gojigi.admin.system.dto.response.MonitorResponses.Call;
import com.sesac.gojigi.admin.system.dto.response.MonitorResponses.Db;
import com.sesac.gojigi.admin.system.dto.response.MonitorResponses.ErrorItem;
import com.sesac.gojigi.admin.system.dto.response.MonitorResponses.ServiceSeries;
import com.sesac.gojigi.admin.system.dto.response.MonitorResponses.Span;
import com.sesac.gojigi.admin.system.dto.response.MonitorResponses.StatusCount;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 관리자 화면 실시간 모니터링(11/16 피드백) — 부하테스트와 상관없이 실제 트래픽을 항상 보여 준다.
 * 그래프 = 게이트웨이 라우트 지표(Prometheus, 5초 수집), 호출 · 트레이스 = Zipkin(샘플링 1.0), 에러 로그 = Loki.
 * 조회는 전부 보조 정보라 실패해도 빈 값으로 응답한다(화면은 "-")
 */
@Service
public class MonitorService {

    static final Duration WINDOW = Duration.ofMinutes(3);
    static final Duration STEP = Duration.ofSeconds(5);
    static final int LIST_LIMIT = 40;
    /** 관리자 시스템 화면이 1 ~ 2초마다 부르는 요청 — 호출 목록을 덮지 않게 뺀다 */
    static final String SELF_PATH = "/api/admin/system";
    /** Prometheus 수집(5초) · 상태 확인 요청 — 게이트웨이 파드를 직접 부르므로 업무 요청이 아니다 */
    static final String ACTUATOR_PATH = "/actuator";
    static final String ROUTE_TAG = "spring.cloud.gateway.route.id";

    static final String GW = "spring_cloud_gateway_requests_seconds";
    static final String COUNT_BY_ROUTE = "sum by (routeId)(rate(" + GW + "_count[15s]))";
    static final String SUM_BY_ROUTE = "sum by (routeId)(rate(" + GW + "_sum[15s]))";
    /** 5xx 건수 — 점마다 최근 1분 동안 몇 건(11/17 피드백: 퍼센트 대신 건수) */
    static final String ERRORS_BY_ROUTE = "sum by (routeId)(increase(" + GW + "_count{httpStatusCode=~\"5..\"}[1m]))";

    /** 분석 대상 — 액추에이터 · 스트림 · WebSocket 과 이 화면 자체 폴링(/api/admin/system/**, 1 ~ 2초)은 뺀다(느린 API 순위를 자기 조회가 차지했음, 11/17) */
    static final String APP_FILTER = "namespace=\"sesac-gojigi\",app!=\"gateway-service\",uri!~\"/actuator.*|UNKNOWN|.*stream.*|/ws.*|/api/admin/system.*\"";
    static final String TOP_GATEWAY_ERRORS = "topk(5, sum by (routeId, httpStatusCode)(increase(" + GW + "_count{httpStatusCode=~\"5..\"}[5m])))";
    static final String TOP_APP_ERRORS = "topk(5, sum by (app, method, uri, status)(increase(http_server_requests_seconds_count{"
            + APP_FILTER + ",status=~\"5..\"}[5m])))";
    static final String SLOWEST = "topk(5, sum by (app, method, uri)(rate(http_server_requests_seconds_sum{" + APP_FILTER + "}[5m]))"
            + " / sum by (app, method, uri)(rate(http_server_requests_seconds_count{" + APP_FILTER + "}[5m])))";
    static final String STATUSES = "sum by (httpStatusCode)(increase(" + GW + "_count[5m]))";

    private final PrometheusClient prometheus;
    private final ZipkinClient zipkin;
    private final LokiClient loki;
    private final Clock clock;

    public MonitorService(PrometheusClient prometheus, ZipkinClient zipkin, LokiClient loki, Clock clock) {
        this.prometheus = prometheus;
        this.zipkin = zipkin;
        this.loki = loki;
        this.clock = clock;
    }

    // ── 총 현황 · 서비스별 그래프

    public MonitorResponses.Overview overview() {
        Instant end = clock.instant();
        Instant start = end.minus(WINDOW);
        Map<String, TreeMap<Long, Double>> count = byService(prometheus.rangeBy(COUNT_BY_ROUTE, "routeId", start, end, STEP));
        Map<String, TreeMap<Long, Double>> sum = byService(prometheus.rangeBy(SUM_BY_ROUTE, "routeId", start, end, STEP));
        Map<String, TreeMap<Long, Double>> errors = byService(prometheus.rangeBy(ERRORS_BY_ROUTE, "routeId", start, end, STEP));
        Map<String, Db> db = dbPools();

        List<ServiceSeries> services = SystemCatalog.SERVICES.stream()
                .map(SystemCatalog.Target::name)
                .filter(SystemCatalog.ROUTE_SERVICES::containsValue)
                .map(name -> new ServiceSeries(name, series(count.get(name), sum.get(name), errors.get(name)), db.get(name)))
                .toList();
        MonitorResponses.Series total = series(total(count), total(sum), total(errors));
        return new MonitorResponses.Overview(OffsetDateTime.ofInstant(end, clock.getZone()), total, services);
    }

    /** 라우트별 시리즈 → 서비스별(라우트 여러 개면 시각별 합). 매핑에 없는 라우트(keycloak 등)는 뺀다 */
    static Map<String, TreeMap<Long, Double>> byService(Optional<Map<String, List<Sample>>> byRoute) {
        Map<String, TreeMap<Long, Double>> result = new HashMap<>();
        byRoute.orElse(Map.of()).forEach((route, samples) -> {
            String service = SystemCatalog.ROUTE_SERVICES.get(route);
            if (service != null) {
                TreeMap<Long, Double> points = result.computeIfAbsent(service, k -> new TreeMap<>());
                samples.forEach(s -> points.merge(s.t(), s.v(), Double::sum));
            }
        });
        return result;
    }

    private static TreeMap<Long, Double> total(Map<String, TreeMap<Long, Double>> byService) {
        TreeMap<Long, Double> total = new TreeMap<>();
        byService.values().forEach(points -> points.forEach((t, v) -> total.merge(t, v, Double::sum)));
        return total;
    }

    /** 요청 수 시각 기준으로 평균 응답(ms) = 합 ÷ 수(요청 0 이면 0), errors = 최근 1분 5xx 건수(정수) */
    static MonitorResponses.Series series(TreeMap<Long, Double> count, TreeMap<Long, Double> sum, TreeMap<Long, Double> errors) {
        List<Sample> rps = new ArrayList<>();
        List<Sample> avgMs = new ArrayList<>();
        List<Sample> errorCount = new ArrayList<>();
        if (count != null) {
            count.forEach((t, c) -> {
                rps.add(new Sample(t, round(c, 2)));
                double s = sum == null ? 0 : sum.getOrDefault(t, 0.0);
                double e = errors == null ? 0 : errors.getOrDefault(t, 0.0);
                avgMs.add(new Sample(t, c > 0 ? round(s / c * 1000, 1) : 0));
                errorCount.add(new Sample(t, Math.round(e)));
            });
        }
        return new MonitorResponses.Series(rps, avgMs, errorCount);
    }

    /** 서비스별 DB 연결 풀 — 파드 합. 조회 실패면 빈 맵 */
    private Map<String, Db> dbPools() {
        Map<String, Double> active = byApp(prometheus.vector("sum by (app)(hikaricp_connections_active)"));
        Map<String, Double> pending = byApp(prometheus.vector("sum by (app)(hikaricp_connections_pending)"));
        Map<String, Double> max = byApp(prometheus.vector("sum by (app)(hikaricp_connections_max)"));
        Map<String, Db> db = new HashMap<>();
        max.forEach((app, m) -> db.put(app, new Db((int) Math.round(active.getOrDefault(app, 0.0)),
                (int) Math.round(pending.getOrDefault(app, 0.0)), (int) Math.round(m))));
        return db;
    }

    private static Map<String, Double> byApp(Optional<List<Series>> series) {
        Map<String, Double> values = new HashMap<>();
        series.orElse(List.of()).forEach(s -> values.put(s.labels().getOrDefault("app", ""), s.value()));
        return values;
    }

    // ── 호출 · 장애 목록

    /** service 가 있으면 그 서비스로 간 요청만(게이트웨이 라우트 태그로 Zipkin 에서 바로 조회) — 트래픽이 적은 서비스도 묻히지 않게 */
    public List<Call> calls(String service) {
        long now = clock.millis();
        return traces(service, null, now, Duration.ofMinutes(1).toMillis(), LIST_LIMIT * 2).stream()
                .map(MonitorService::call)
                .filter(Objects::nonNull)
                .filter(c -> service == null || service.equals(c.service()))
                .sorted(Comparator.comparingLong(Call::timestampMs).reversed())
                .limit(LIST_LIMIT)
                .toList();
    }

    public List<ErrorItem> errors(String service) {
        Instant now = clock.instant();
        Stream<ErrorItem> responses = traces(service, "outcome=SERVER_ERROR", now.toEpochMilli(), Duration.ofMinutes(10).toMillis(), LIST_LIMIT)
                .stream().map(MonitorService::call).filter(Objects::nonNull)
                .filter(c -> service == null || service.equals(c.service()))
                .map(c -> new ErrorItem("RESPONSE", c.timestampMs(), c.service(), "ERROR", c.method() + " " + c.path(), c.status(), c.traceId()));
        Stream<ErrorItem> logs = loki.errors(now.minus(Duration.ofMinutes(10)), now, LIST_LIMIT, service).stream()
                .map(l -> new ErrorItem("LOG", l.timestampMs(), l.service(), l.level(), l.message(), null, l.traceId()));
        return Stream.concat(responses, logs)
                .sorted(Comparator.comparingLong(ErrorItem::timestampMs).reversed())
                .limit(LIST_LIMIT)
                .toList();
    }

    /** 서비스 필터가 있으면 그 서비스의 게이트웨이 라우트마다 조회해 합친다(user 는 user-auth · user-auth-strict · user-service) */
    private List<JsonNode> traces(String service, String condition, long endTsMs, long lookbackMs, int limit) {
        if (service == null) {
            return zipkin.recentTraces(condition, endTsMs, lookbackMs, limit);
        }
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        SystemCatalog.ROUTE_SERVICES.forEach((route, owner) -> {
            if (owner.equals(service)) {
                String query = (condition == null ? "" : condition + " and ") + ROUTE_TAG + "=" + route;
                zipkin.recentTraces(query, endTsMs, lookbackMs, limit)
                        .forEach(tr -> byId.putIfAbsent(tr.path(0).path("traceId").asString(""), tr));
            }
        });
        return List.copyOf(byId.values());
    }

    /** 필터로 받을 수 있는 서비스 — 게이트웨이 라우트가 있는 서비스만 */
    public static boolean isFilterable(String service) {
        return service == null || SystemCatalog.ROUTE_SERVICES.containsValue(service);
    }

    /** 트레이스 하나 → 게이트웨이 첫 구간(SERVER, 부모 없음)으로 요청 한 줄. 관리자 시스템 화면 폴링 · 액추에이터 · 경로 없는 것은 null */
    static Call call(JsonNode trace) {
        JsonNode root = null;
        String route = null;
        for (JsonNode span : trace) {
            boolean gateway = ZipkinClient.GATEWAY.equals(span.path("localEndpoint").path("serviceName").asString(null));
            if (gateway && "SERVER".equals(span.path("kind").asString(null)) && !span.hasNonNull("parentId")) {
                root = span;
            }
            if (route == null && span.path("tags").has(ROUTE_TAG)) {
                route = span.path("tags").path(ROUTE_TAG).asString();
            }
        }
        if (root == null) {
            return null;
        }
        JsonNode tags = root.path("tags");
        String path = tags.path("http.url").asString(null);
        if (path == null || path.startsWith(SELF_PATH) || path.startsWith(ACTUATOR_PATH)) {
            return null;
        }
        String service = route == null ? null : SystemCatalog.ROUTE_SERVICES.getOrDefault(route, route);
        return new Call(root.path("traceId").asString(), root.path("timestamp").asLong() / 1000, tags.path("method").asString(null), path,
                service, status(tags), round(root.path("duration").asLong() / 1000.0, 1));
    }

    // ── 트레이스

    public MonitorResponses.Trace trace(String traceId) {
        List<JsonNode> spans = zipkin.trace(traceId);
        long start = spans.stream().mapToLong(s -> s.path("timestamp").asLong()).min().orElse(0);
        long end = spans.stream().mapToLong(s -> s.path("timestamp").asLong() + s.path("duration").asLong()).max().orElse(0);
        List<Span> items = spans.stream()
                .sorted(Comparator.comparingLong(s -> s.path("timestamp").asLong()))
                .map(s -> {
                    JsonNode tags = s.path("tags");
                    Integer status = status(tags);
                    String path = tags.path("http.url").asString(tags.path("uri").asString(null));
                    String name = s.path("name").asString("") + (path == null || "UNKNOWN".equals(path) ? "" : " " + path);
                    return new Span(s.path("id").asString(), s.path("parentId").asString(null),
                            s.path("localEndpoint").path("serviceName").asString(null), name, s.path("kind").asString(null),
                            round((s.path("timestamp").asLong() - start) / 1000.0, 1), round(s.path("duration").asLong() / 1000.0, 1),
                            status, tags.has("error") || (status != null && status >= 500));
                })
                .toList();
        return new MonitorResponses.Trace(traceId, round((end - start) / 1000.0, 1), items);
    }

    /** 상태 코드 — 서버 구간은 status, 클라이언트 구간은 http.status_code */
    private static Integer status(JsonNode tags) {
        String value = tags.path("status").asString(tags.path("http.status_code").asString(null));
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── 분석 (최근 5분)

    public MonitorResponses.Summary summary() {
        List<ApiCount> errors = new ArrayList<>();
        prometheus.vector(TOP_GATEWAY_ERRORS).orElse(List.of()).forEach(s -> {
            String route = s.labels().getOrDefault("routeId", "");
            errors.add(new ApiCount(SystemCatalog.ROUTE_SERVICES.getOrDefault(route, route), "게이트웨이 라우트 " + route,
                    s.labels().getOrDefault("httpStatusCode", ""), Math.round(s.value())));
        });
        prometheus.vector(TOP_APP_ERRORS).orElse(List.of()).forEach(s -> errors.add(new ApiCount(s.labels().getOrDefault("app", ""),
                s.labels().getOrDefault("method", "") + " " + s.labels().getOrDefault("uri", ""), s.labels().getOrDefault("status", ""),
                Math.round(s.value()))));
        List<ApiCount> topErrors = errors.stream().filter(e -> e.count() > 0)
                .sorted(Comparator.comparingLong(ApiCount::count).reversed()).limit(5).toList();

        List<ApiLatency> slowest = prometheus.vector(SLOWEST).orElse(List.of()).stream()
                .map(s -> new ApiLatency(s.labels().getOrDefault("app", ""),
                        s.labels().getOrDefault("method", "") + " " + s.labels().getOrDefault("uri", ""), round(s.value() * 1000, 1)))
                .sorted(Comparator.comparingDouble(ApiLatency::avgMs).reversed())
                .toList();

        List<StatusCount> statuses = prometheus.vector(STATUSES).orElse(List.of()).stream()
                .map(s -> new StatusCount(s.labels().getOrDefault("httpStatusCode", ""), Math.round(s.value())))
                .filter(s -> s.count() > 0)
                .sorted(Comparator.comparing(StatusCount::status))
                .toList();
        return new MonitorResponses.Summary(topErrors, slowest, statuses);
    }

    private static double round(double value, int digits) {
        double scale = Math.pow(10, digits);
        return Math.round(value * scale) / scale;
    }
}
