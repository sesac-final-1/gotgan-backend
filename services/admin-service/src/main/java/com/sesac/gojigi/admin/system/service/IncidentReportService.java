package com.sesac.gojigi.admin.system.service;

import com.sesac.gojigi.admin.system.client.LokiClient;
import com.sesac.gojigi.admin.system.client.PrometheusClient;
import com.sesac.gojigi.admin.system.client.PrometheusClient.RangeSeries;
import com.sesac.gojigi.admin.system.client.PrometheusClient.Sample;
import com.sesac.gojigi.admin.system.client.PrometheusClient.Series;
import com.sesac.gojigi.admin.system.dto.response.MonitorResponses.ErrorItem;
import com.sesac.gojigi.admin.system.dto.response.IncidentReportResponses.Bucket;
import com.sesac.gojigi.admin.system.dto.response.IncidentReportResponses.Cause;
import com.sesac.gojigi.admin.system.dto.response.IncidentReportResponses.Event;
import com.sesac.gojigi.admin.system.dto.response.IncidentReportResponses.Issue;
import com.sesac.gojigi.admin.system.dto.response.IncidentReportResponses.Report;
import com.sesac.gojigi.admin.system.dto.response.IncidentReportResponses.Summary;
import com.sesac.gojigi.admin.system.entity.ChaosEvent;
import com.sesac.gojigi.admin.system.exception.SystemErrorCode;
import com.sesac.gojigi.admin.system.exception.SystemException;
import com.sesac.gojigi.admin.system.repository.ChaosEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 장애 분석 리포트 (11/17 발표 피드백) — "언제, 어디서, 몇 건, 왜" 를 운영팀이 읽을 수 있게.
 * 1) 기간을 구간(bucket)으로 나눠 구간마다 5xx, 요청, 429, 탐색 요청, DB 오류, 에러 로그 건수(Prometheus, Loki)
 * 2) 이상 신호가 이어진 구간을 이슈 하나로 묶고
 * 3) 같은 시간대의 근거(장애 주입 기록, 부하테스트, 파드 재시작, 종료, 배포, DB 오류, 요청 급증, 탐색 요청, 차단기)로 원인을 고른다 — 규칙 기반
 * 데이터 보존: Prometheus, Loki 7일 → 조회 기간도 최근 7일 안. 지표 조회가 실패하면 그 항목만 0 으로(리포트는 나간다)
 */
@Service
public class IncidentReportService {

    static final Duration MAX_RANGE = Duration.ofDays(7);
    static final int ISSUE_LOG_LIMIT = 15;
    static final int EVIDENCE_ISSUES = 10;
    static final int SUSPICIOUS_LOG_LIMIT = 20;
    static final int MAX_EVENTS = 300;
    /** 요청 급증 — 평소(구간 중앙값)의 몇 배 이상이고, 분당 최소 몇 건 이상일 때 */
    static final double SURGE_FACTOR = 3.0;
    static final long SURGE_MIN_PER_MINUTE = 100;
    /** 요청 제한(429) 이 구간에 이만큼 넘으면 이슈 */
    static final long RATE_LIMIT_MIN = 10;

    private static final String[] KINDS = {"ERROR", "ATTACK", "TRAFFIC"};
    static final String NS = "namespace=\"sesac-gojigi\"";
    static final String GW_COUNT = MonitorService.GW + "_count";
    static final String DB_ERROR_PATTERN = "(?i)sqlexception|jdbc|hikaripool|connection is not available|deadlock|lock wait timeout|communications link failure";
    static final String SUSPICIOUS_LOG = "{" + NS + ",app=\"gateway-service\"} |= \"의심 요청\"";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final Set<String> SERVICE_NAMES = SystemCatalog.SERVICES.stream().map(SystemCatalog.Target::name)
            .collect(Collectors.toUnmodifiableSet());
    static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("order-service", "주문"), Map.entry("product-service", "상품"), Map.entry("payment-service", "결제"),
            Map.entry("store-service", "스토어"), Map.entry("user-service", "회원"), Map.entry("review-service", "리뷰"),
            Map.entry("settlement-service", "정산"), Map.entry("chat-service", "채팅"), Map.entry("gateway-service", "게이트웨이"),
            Map.entry("admin-service", "관리자"), Map.entry("keycloak", "로그인(Keycloak)"), Map.entry("mysql", "DB"),
            Map.entry("redis", "Redis"));
    private static final Map<String, String> SUSPICIOUS_TYPES = Map.of("secret-file", "설정, 비밀 파일 탐색(/.env 등)",
            "traversal", "경로 조작(../)", "cms-probe", "워드프레스, 관리 도구 탐색", "script-probe", "스크립트 파일 탐색(.php 등)");

    private final PrometheusClient prometheus;
    private final LokiClient loki;
    private final ChaosEventRepository chaosEvents;
    private final Clock clock;
    /** 점검용 요청 시간대 [시작 ms, 끝 ms] — 우리가 일부러 보낸 탐색 경로 요청(방어 확인 등)을 공격 의심에서 뺀다 */
    private final List<long[]> testWindows;

    public IncidentReportService(PrometheusClient prometheus, LokiClient loki, ChaosEventRepository chaosEvents, Clock clock,
            @Value("${app.report.test-windows:}") List<String> testWindows) {
        this.prometheus = prometheus;
        this.loki = loki;
        this.chaosEvents = chaosEvents;
        this.clock = clock;
        this.testWindows = testWindows.stream().filter(w -> w.contains("/")).map(w -> {
            String[] p = w.trim().split("/");
            return new long[] {OffsetDateTime.parse(p[0]).toInstant().toEpochMilli(), OffsetDateTime.parse(p[1]).toInstant().toEpochMilli()};
        }).toList();
    }

    boolean inTestWindow(long ms) {
        return testWindows.stream().anyMatch(w -> ms >= w[0] && ms < w[1]);
    }

    private static final java.util.regex.Pattern PROBE_TYPE = java.util.regex.Pattern.compile("type=([a-z-]+)");

    /** 점검용 시간대의 탐색 요청을 구간별 건수에서 뺀다 — 로그(Loki)로 유형과 시각을 알아내 그 구간 값에서 1씩 */
    private void excludeTestProbes(Metrics m, Instant start, Instant stop, long b) {
        for (long[] w : testWindows) {
            if (w[1] <= start.toEpochMilli() || w[0] >= stop.toEpochMilli()) {
                continue;
            }
            for (LokiClient.LogLine l : loki.lines(SUSPICIOUS_LOG, Instant.ofEpochMilli(w[0]), Instant.ofEpochMilli(w[1]), 100)) {
                java.util.regex.Matcher type = PROBE_TYPE.matcher(l.message());
                if (!type.find() || !inTestWindow(l.timestampMs())) {
                    continue;
                }
                long at = start.toEpochMilli() + ((l.timestampMs() - start.toEpochMilli()) / b + 1) * b;
                m.suspiciousByType().computeIfAbsent(type.group(1), k -> new HashMap<>()).merge(at, -1.0, (old, d) -> Math.max(0, old + d));
            }
        }
    }

    /** 기간이 길수록 구간을 넓게 — 막대가 너무 많거나 적지 않게 */
    static Duration bucketFor(Duration range) {
        if (range.compareTo(Duration.ofHours(3)) <= 0) {
            return Duration.ofMinutes(1);
        }
        if (range.compareTo(Duration.ofHours(24)) <= 0) {
            return Duration.ofMinutes(5);
        }
        if (range.compareTo(Duration.ofDays(3)) <= 0) {
            return Duration.ofMinutes(15);
        }
        return Duration.ofHours(1);
    }

    public Report report(Instant from, Instant to) {
        Instant now = clock.instant();
        Instant end = to.isAfter(now) ? now : to;
        if (!from.isBefore(end) || Duration.between(from, end).compareTo(MAX_RANGE) > 0 || from.isBefore(now.minus(MAX_RANGE))) {
            throw new SystemException(SystemErrorCode.SYSTEM_REPORT_INVALID_RANGE);
        }
        Duration bucket = bucketFor(Duration.between(from, end));
        long b = bucket.toMillis();
        Instant start = Instant.ofEpochMilli(from.toEpochMilli() / b * b);
        Instant stop = Instant.ofEpochMilli((end.toEpochMilli() + b - 1) / b * b);

        Metrics m = collect(start, stop, bucket);
        excludeTestProbes(m, start, stop, b);
        List<Bucket> buckets = buckets(m, start, stop, b);
        Context ctx = context(start, stop, bucket, m);
        List<Issue> issues = issues(buckets, ctx, b);
        List<Event> events = events(ctx, start, stop);
        List<ErrorItem> suspicious = loki.lines(SUSPICIOUS_LOG, start, stop, SUSPICIOUS_LOG_LIMIT).stream()
                .filter(l -> !inTestWindow(l.timestampMs()))
                .map(l -> new ErrorItem("LOG", l.timestampMs(), l.service(), l.level(), l.message(), null, l.traceId())).toList();
        return new Report(OffsetDateTime.now(clock), start.toEpochMilli(), stop.toEpochMilli(), (int) bucket.toSeconds(),
                summary(buckets, issues, start, stop), buckets, issues, events, suspicious);
    }

    // ── 1) 구간별 건수 ────────────────────────────────────────────

    /** 구간 끝 시각(ms) → 값. Prometheus range 는 [t - 구간, t] 의 increase 를 t 에 찍는다 */
    record Metrics(Map<String, Map<Long, Double>> errorsByRoute, Map<String, Map<Long, Double>> byStatus,
                   Map<String, Map<Long, Double>> suspiciousByType, Map<String, Map<Long, Double>> circuitOpen,
                   Map<String, Map<Long, Double>> dbPending, Map<String, Map<Long, Double>> restartsByPod,
                   Map<String, Map<Long, Double>> errorLogs, Map<String, Map<Long, Double>> dbErrorLogs) {
    }

    private Metrics collect(Instant start, Instant stop, Duration bucket) {
        String w = "[" + bucket.toSeconds() + "s]";
        Instant first = start.plus(bucket);
        return new Metrics(
                prom("sum by (routeId)(increase(" + GW_COUNT + "{httpStatusCode=~\"5..\"}" + w + "))", "routeId", first, stop, bucket),
                prom("sum by (httpStatusCode)(increase(" + GW_COUNT + w + "))", "httpStatusCode", first, stop, bucket),
                prom("sum by (type)(increase(gateway_suspicious_requests_total" + w + "))", "type", first, stop, bucket),
                prom("max by (app, name)(max_over_time(resilience4j_circuitbreaker_state{" + NS + ",state=\"open\"}" + w + "))",
                        s -> s.get("app") + ", " + s.get("name"), first, stop, bucket),
                prom("max by (app)(max_over_time(hikaricp_connections_pending{" + NS + "}" + w + "))", "app", first, stop, bucket),
                prom("sum by (pod)(increase(kube_pod_container_status_restarts_total{" + NS + "}" + w + "))", "pod", first, stop, bucket),
                loki.countBy("sum by (app)(count_over_time({" + NS + ",level=\"ERROR\"}" + w + "))", "app", first, stop, bucket),
                loki.countBy("sum by (app)(count_over_time({" + NS + ",level=~\"WARN|ERROR\"} |~ \"" + DB_ERROR_PATTERN + "\"" + w + "))",
                        "app", first, stop, bucket));
    }

    private Map<String, Map<Long, Double>> prom(String promql, String label, Instant start, Instant end, Duration step) {
        return prom(promql, labels -> labels.getOrDefault(label, ""), start, end, step);
    }

    private Map<String, Map<Long, Double>> prom(String promql, Function<Map<String, String>, String> key, Instant start, Instant end,
            Duration step) {
        Map<String, Map<Long, Double>> result = new HashMap<>();
        for (RangeSeries s : prometheus.matrix(promql, start, end, step).orElse(List.of())) {
            Map<Long, Double> points = result.computeIfAbsent(key.apply(s.labels()), k -> new HashMap<>());
            for (Sample p : s.samples()) {
                points.merge(p.t(), p.v(), Double::sum);
            }
        }
        return result;
    }

    private static List<Bucket> buckets(Metrics m, Instant start, Instant stop, long b) {
        List<Bucket> buckets = new ArrayList<>();
        for (long t = start.toEpochMilli(); t < stop.toEpochMilli(); t += b) {
            long at = t + b;
            Map<String, Long> byService = new TreeMap<>();
            m.errorsByRoute().forEach((route, points) -> {
                long n = round(points.get(at));
                if (n > 0) {
                    byService.merge(SystemCatalog.ROUTE_SERVICES.getOrDefault(route, route), n, Long::sum);
                }
            });
            long requests = sum(m.byStatus(), at, k -> true);
            long serverErrors = byService.values().stream().mapToLong(Long::longValue).sum();
            buckets.add(new Bucket(t, requests, serverErrors, byService, sum(m.byStatus(), at, "429"::equals),
                    sum(m.byStatus(), at, "404"::equals), sum(m.byStatus(), at, k -> "401".equals(k) || "403".equals(k)),
                    sum(m.suspiciousByType(), at, k -> true), sum(m.dbErrorLogs(), at, k -> true), sum(m.errorLogs(), at, k -> true)));
        }
        return buckets;
    }

    private static long sum(Map<String, Map<Long, Double>> series, long at, Predicate<String> key) {
        return series.entrySet().stream().filter(e -> key.test(e.getKey())).mapToLong(e -> round(e.getValue().get(at))).sum();
    }

    private static long round(Double v) {
        return v == null ? 0 : Math.round(v);
    }

    // ── 2) 근거 (파드, 배포, 부하테스트, 장애 주입) ────────────────

    /** alive = 기간 끝에도 있는 파드. lastSeenMs = 마지막으로 보인 시각(1분 해상도) */
    record PodLife(String pod, String service, long createdMs, long lastSeenMs, boolean alive) {
    }

    record LoadTest(String id, long startMs, long endMs, int peakVus) {
    }

    record Rollout(String service, long t) {
    }

    /** scaleDowns = 서비스 → 원하는 파드 수(replicas)가 줄어든 시각들(HPA 자동 축소, 수동 축소) */
    record Context(Metrics metrics, List<PodLife> pods, Set<String> oomPods, List<Rollout> rollouts, List<LoadTest> loadTests,
                   List<ChaosEvent> chaos, long medianRequests, Map<String, List<Long>> scaleDowns) {
    }

    private Context context(Instant start, Instant stop, Duration bucket, Metrics m) {
        String r = "[" + Duration.between(start, stop).toSeconds() + "s]";
        // 사라진 시각은 1분 해상도로(구간 크기로 하면 5분, 1시간 단위로 뭉개져 다른 사건과 섞인다)
        String sub = "[" + Duration.between(start, stop).toSeconds() + "s:" + Math.min(bucket.toSeconds(), 60) + "s]";
        Map<String, Double> created = byLabel(prometheus.vector("last_over_time(kube_pod_created{" + NS + "}" + r + ")", stop), "pod");
        Map<String, Double> lastSeen = byLabel(prometheus.vector("max_over_time(timestamp(kube_pod_created{" + NS + "})" + sub + ")", stop), "pod");
        Set<String> alive = byLabel(prometheus.vector("kube_pod_created{" + NS + "}", stop), "pod").keySet();
        List<PodLife> pods = new ArrayList<>();
        created.forEach((pod, c) -> {
            String service = serviceOf(pod);
            if (service != null) {
                pods.add(new PodLife(pod, service, Math.round(c * 1000), Math.round(lastSeen.getOrDefault(pod, 0.0) * 1000),
                        alive.contains(pod)));
            }
        });
        Set<String> oom = byLabel(prometheus.vector("max by (pod)(max_over_time(kube_pod_container_status_last_terminated_reason{" + NS
                + ",reason=\"OOMKilled\"}" + r + ")) > 0", stop), "pod").keySet();
        List<Rollout> rollouts = new ArrayList<>();
        byLabel(prometheus.vector("last_over_time(kube_replicaset_created{" + NS + "}" + r + ")", stop), "replicaset").forEach((rs, c) -> {
            long t = Math.round(c * 1000);
            String service = serviceOf(rs + "-x");
            if (service != null && t >= start.toEpochMilli() && t < stop.toEpochMilli()) {
                rollouts.add(new Rollout(service, t));
            }
        });
        String fine = "[" + Duration.between(start, stop).toSeconds() + "s:15s]";
        Map<String, Double> ltStart = byLabel(prometheus.vector("min by (testid)(min_over_time(timestamp(k6_vus)" + fine + "))", stop), "testid");
        Map<String, Double> ltEnd = byLabel(prometheus.vector("max by (testid)(max_over_time(timestamp(k6_vus)" + fine + "))", stop), "testid");
        Map<String, Double> ltPeak = byLabel(prometheus.vector("max by (testid)(max_over_time(k6_vus" + r + "))", stop), "testid");
        List<LoadTest> loadTests = ltStart.entrySet().stream().map(e -> new LoadTest(e.getKey(), Math.round(e.getValue() * 1000),
                Math.round(ltEnd.getOrDefault(e.getKey(), e.getValue()) * 1000), (int) Math.round(ltPeak.getOrDefault(e.getKey(), 0.0))))
                .sorted(Comparator.comparingLong(LoadTest::startMs)).toList();
        List<ChaosEvent> chaos = chaosEvents.findByKilledAtBetweenOrderByKilledAtAsc(LocalDateTime.ofInstant(start, KST),
                LocalDateTime.ofInstant(stop, KST));
        return new Context(m, pods, oom, rollouts, loadTests, chaos, median(m), scaleDowns(start, stop));
    }

    /** Deployment 원하는 파드 수가 직전 점보다 줄어든 시각(1분 간격) — 줄어든 만큼 파드가 지워지는 것은 정상 */
    private Map<String, List<Long>> scaleDowns(Instant start, Instant stop) {
        Map<String, List<Long>> result = new HashMap<>();
        for (RangeSeries s : prometheus.matrix("max by (deployment)(kube_deployment_spec_replicas{" + NS + "})", start, stop,
                Duration.ofMinutes(1)).orElse(List.of())) {
            List<Sample> points = s.samples();
            for (int i = 1; i < points.size(); i++) {
                if (points.get(i).v() < points.get(i - 1).v()) {
                    result.computeIfAbsent(s.labels().getOrDefault("deployment", ""), k -> new ArrayList<>()).add(points.get(i).t());
                }
            }
        }
        return result;
    }

    private static Map<String, Double> byLabel(Optional<List<Series>> series, String label) {
        Map<String, Double> values = new HashMap<>();
        series.orElse(List.of()).forEach(s -> values.merge(s.labels().getOrDefault(label, ""), s.value(), Math::max));
        return values;
    }

    /** 파드, 레플리카셋 이름 → 서비스(SystemCatalog 이름). product-service-757dd8467b-ms5nt → product-service, mysql-0 → mysql */
    static String serviceOf(String pod) {
        for (String name : SERVICE_NAMES) {
            if (pod.startsWith(name + "-")) {
                return name;
            }
        }
        return null;
    }

    /** 평소 요청 수 — 요청이 있었던 구간의 중앙값 */
    private static long median(Metrics m) {
        Map<Long, Long> perBucket = new HashMap<>();
        m.byStatus().values().forEach(points -> points.forEach((t, v) -> perBucket.merge(t, round(v), Long::sum)));
        List<Long> values = perBucket.values().stream().filter(v -> v > 0).sorted().toList();
        return values.isEmpty() ? 0 : values.get(values.size() / 2);
    }

    // ── 3) 이슈 묶기, 원인 분류 ──────────────────────────────────

    private List<Issue> issues(List<Bucket> buckets, Context ctx, long b) {
        long surgeMin = SURGE_MIN_PER_MINUTE * b / 60_000;
        List<long[]> windows = new ArrayList<>();
        windows.addAll(group(buckets, x -> x.serverErrors() > 0 || x.dbErrors() > 0 || circuitOpenAt(ctx, x.t() + b), b, "ERROR"));
        windows.addAll(group(buckets, x -> x.suspicious() > 0, b, "ATTACK"));
        windows.addAll(group(buckets, x -> x.rateLimited() >= RATE_LIMIT_MIN
                || (x.requests() >= surgeMin && ctx.medianRequests() > 0 && x.requests() >= SURGE_FACTOR * ctx.medianRequests()), b, "TRAFFIC"));
        windows.sort(Comparator.comparingLong(w -> w[0]));
        List<Issue> issues = new ArrayList<>();
        for (long[] w : windows) {
            String kind = KINDS[(int) w[2]];
            if ("TRAFFIC".equals(kind) && overlapsError(windows, w)) {
                continue;   // 같은 시간에 응답 실패가 있으면 ERROR 이슈의 원인(요청 급증)으로만
            }
            issues.add(issue(issues.size() + 1, kind, w[0], w[1], buckets, ctx, b));
        }
        return issues;
    }


    /** 조건이 맞는 구간을 이어 붙인다 — 사이에 1구간 비어도 같은 이슈. [시작 ms, 끝 ms, 종류 번호] */
    private static List<long[]> group(List<Bucket> buckets, Predicate<Bucket> hit, long b, String kind) {
        long kindNo = List.of(KINDS).indexOf(kind);
        List<long[]> windows = new ArrayList<>();
        long[] open = null;
        for (Bucket x : buckets) {
            if (!hit.test(x)) {
                continue;
            }
            if (open != null && x.t() <= open[1] + b) {
                open[1] = x.t() + b;
            } else {
                open = new long[] {x.t(), x.t() + b, kindNo};
                windows.add(open);
            }
        }
        return windows;
    }

    private static boolean overlapsError(List<long[]> windows, long[] w) {
        return windows.stream().anyMatch(o -> o[2] == 0 && o[0] < w[1] && w[0] < o[1]);
    }

    private static boolean circuitOpenAt(Context ctx, long at) {
        return ctx.metrics().circuitOpen().values().stream().anyMatch(points -> round(points.get(at)) > 0);
    }

    private Issue issue(int no, String kind, long s, long e, List<Bucket> buckets, Context ctx, long b) {
        List<Bucket> in = buckets.stream().filter(x -> x.t() >= s && x.t() < e).toList();
        Map<String, Long> byService = new TreeMap<>();
        in.forEach(x -> x.byService().forEach((k, v) -> byService.merge(k, v, Long::sum)));
        long serverErrors = in.stream().mapToLong(Bucket::serverErrors).sum();
        long suspicious = in.stream().mapToLong(Bucket::suspicious).sum();
        long rateLimited = in.stream().mapToLong(Bucket::rateLimited).sum();
        long requests = in.stream().mapToLong(Bucket::requests).sum();
        long peak = in.stream().mapToLong(Bucket::requests).max().orElse(0);
        List<String> services = byService.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(Map.Entry::getKey).toList();
        List<Cause> causes = causes(kind, s - b, e + b, in, ctx, b);
        if ("ATTACK".equals(kind)) {
            // 외부 탐색은 우리 시험(장애 주입, 부하테스트)과 무관 — 같은 시간에 시험이 있어도 원인에서 빼고 늘 실제로 본다
            causes = causes.stream().filter(c -> !"CHAOS".equals(c.code()) && !"LOAD_TEST".equals(c.code())).toList();
        }
        boolean intended = !"ATTACK".equals(kind)
                && causes.stream().anyMatch(c -> "CHAOS".equals(c.code()) || "LOAD_TEST".equals(c.code()));
        List<ErrorItem> logs = no <= EVIDENCE_ISSUES
                ? loki.lines(LokiClient.ERROR_QUERY, Instant.ofEpochMilli(s - b), Instant.ofEpochMilli(e), ISSUE_LOG_LIMIT).stream()
                        .filter(l -> !(inTestWindow(l.timestampMs()) && PROBE_TYPE.matcher(l.message()).find()))
                        .map(l -> new ErrorItem("LOG", l.timestampMs(), l.service(), l.level(), l.message(), null, l.traceId())).toList()
                : List.of();
        return new Issue(no, kind, s, e, services, serverErrors, suspicious, rateLimited, requests, intended,
                sentence(kind, s, e, services, new long[] {serverErrors, suspicious, rateLimited, peak}, causes), causes, logs);
    }

    /** 원인 후보 — 시간대(앞뒤 1구간 여유)가 겹치는 근거를 모두 모은다. 앞이 주 원인, 같은 종류는 한 줄로 합친다 */
    private static List<Cause> causes(String kind, long from, long to, List<Bucket> in, Context ctx, long b) {
        List<Cause> causes = new ArrayList<>();
        // 장애 주입으로 지운 파드는 기간 전체에서 모은다 — 사라진 시각이 다음 이슈 창에 걸려도 '비정상 종료'로 보지 않게
        Set<String> chaosPods = ctx.chaos().stream().flatMap(c -> c.deletedPodList().stream()).collect(Collectors.toSet());
        for (ChaosEvent c : ctx.chaos()) {
            long t = c.getKilledAt().atZone(KST).toInstant().toEpochMilli();
            if (t >= from && t < to) {
                String recovery = c.recoverySeconds() == null ? "" : ", " + c.recoverySeconds() + "초 뒤 복구";
                causes.add(new Cause("CHAOS", "관리자 장애 주입(의도한 시험)",
                        label(c.getServiceName()) + " 파드 " + c.deletedPodList().size() + "개 강제 종료 " + time(t) + recovery));
            }
        }
        ctx.loadTests().stream().filter(l -> l.startMs() < to && from < l.endMs() + b).forEach(l -> causes.add(new Cause("LOAD_TEST",
                "부하테스트 중(의도한 시험)", "최대 " + l.peakVus() + "명, " + time(l.startMs()) + "~" + time(l.endMs()))));
        podCauses(from, to, ctx, chaosPods, causes);
        ctx.rollouts().stream().filter(r -> r.t() >= from && r.t() < to).map(Rollout::service).distinct()
                .forEach(svc -> causes.add(new Cause("DEPLOY", "배포(롤아웃) 진행 중", label(svc) + " 새 버전 배포가 같은 시간에 있었음")));
        dbCauses(in, ctx, causes, b);
        trafficCauses(in, ctx, causes, b);
        circuitCauses(in, ctx, causes, b);
        if (causes.isEmpty()) {
            causes.add(new Cause("UNKNOWN", "원인 미상", "같은 시간대 근거가 없음 — 개발자 섹션의 로그 확인"));
        }
        if ("ATTACK".equals(kind)) {
            causes.sort(Comparator.comparing(c -> !"ATTACK".equals(c.code())));
        }
        return merge(causes);
    }

    /** 같은 code 는 한 줄로 — 처음 나온 순서 유지, 근거는 이어 붙이고 4개가 넘으면 '외 n건' */
    static List<Cause> merge(List<Cause> causes) {
        Map<String, List<Cause>> byCode = new java.util.LinkedHashMap<>();
        causes.forEach(c -> byCode.computeIfAbsent(c.code(), k -> new ArrayList<>()).add(c));
        return byCode.values().stream().map(same -> {
            List<String> details = same.stream().map(Cause::detail).distinct().toList();
            String detail = String.join(", ", details.subList(0, Math.min(4, details.size())))
                    + (details.size() > 4 ? " 외 " + (details.size() - 4) + "건" : "");
            return new Cause(same.get(0).code(), same.get(0).label(), detail);
        }).toList();
    }

    private static void podCauses(long from, long to, Context ctx, Set<String> chaosPods, List<Cause> causes) {
        Map<String, Long> restarts = new TreeMap<>();
        ctx.metrics().restartsByPod().forEach((pod, points) -> points.forEach((t, v) -> {
            if (t > from && t <= to + 1 && round(v) > 0 && serviceOf(pod) != null) {
                restarts.merge(pod, round(v), Long::sum);
            }
        }));
        restarts.forEach((pod, n) -> causes.add(ctx.oomPods().contains(pod)
                ? new Cause("OOM", "메모리 부족으로 파드 재시작", label(serviceOf(pod)) + " " + pod + " 재시작 " + n + "회(OOMKilled)")
                : new Cause("POD_RESTART", "파드 재시작", label(serviceOf(pod)) + " " + pod + " 재시작 " + n + "회")));
        ctx.pods().stream().filter(p -> stoppedIn(p, from, to) && stopReason(p, ctx, chaosPods) == null)
                .forEach(p -> causes.add(new Cause("POD_DOWN", "파드 비정상 종료", label(p.service()) + " " + p.pod() + " " + time(p.lastSeenMs()) + "쯤 사라짐")));
    }

    /**
     * 파드가 사라진 이유 — 장애 주입(관리자가 지운 파드), 배포 교체(같은 서비스 새 레플리카셋, 15분 안), 
     * 자동 축소(같은 서비스 replicas 감소, 앞뒤 3분 — 사라진 시각은 1분 해상도). 설명되지 않으면 null = 비정상 종료
     */
    static String stopReason(PodLife p, Context ctx, Set<String> chaosPods) {
        long t = p.lastSeenMs();
        if (chaosPods.contains(p.pod())) {
            return "장애 주입";
        }
        if (ctx.rollouts().stream().anyMatch(r -> r.service().equals(p.service()) && r.t() >= t - 900_000 && r.t() <= t + 180_000)) {
            return "배포 교체";
        }
        if (ctx.scaleDowns().getOrDefault(p.service(), List.of()).stream().anyMatch(d -> Math.abs(d - t) <= 180_000)) {
            return "자동 축소";
        }
        return null;
    }

    /** 기간 끝에 없는 파드 = 마지막으로 보인 시각에 종료 */
    private static boolean stoppedIn(PodLife p, long from, long to) {
        return !p.alive() && p.lastSeenMs() > 0 && p.lastSeenMs() >= from && p.lastSeenMs() < to;
    }

    private static void dbCauses(List<Bucket> in, Context ctx, List<Cause> causes, long b) {
        long dbErrors = in.stream().mapToLong(Bucket::dbErrors).sum();
        Set<String> apps = new LinkedHashSet<>();
        in.forEach(x -> ctx.metrics().dbErrorLogs().forEach((app, points) -> {
            if (round(points.get(x.t() + b)) > 0) {
                apps.add(app);
            }
        }));
        Set<String> pending = new LinkedHashSet<>();
        in.forEach(x -> ctx.metrics().dbPending().forEach((app, points) -> {
            if (round(points.get(x.t() + b)) > 0) {
                pending.add(app);
            }
        }));
        if (dbErrors > 0) {
            causes.add(new Cause("DB", "DB 오류", "DB 관련 오류 로그 " + dbErrors + "건 (" + labels(apps) + ")"));
        } else if (!pending.isEmpty()) {
            causes.add(new Cause("DB", "DB 연결 대기", labels(pending) + " 에서 DB 연결을 기다린 요청이 있었음"));
        }
    }

    private static void trafficCauses(List<Bucket> in, Context ctx, List<Cause> causes, long b) {
        long peak = in.stream().mapToLong(Bucket::requests).max().orElse(0);
        if (ctx.medianRequests() > 0 && peak >= SURGE_FACTOR * ctx.medianRequests()) {
            causes.add(new Cause("SURGE", "요청 급증", "구간 최대 " + peak + "건, 평소(" + ctx.medianRequests() + "건)의 "
                    + Math.round((double) peak / ctx.medianRequests()) + "배"));
        }
        long rateLimited = in.stream().mapToLong(Bucket::rateLimited).sum();
        if (rateLimited > 0) {
            causes.add(new Cause("RATE_LIMIT", "요청 제한 초과", "429 응답 " + rateLimited + "건 — 한 사용자, IP 가 너무 자주 요청"));
        }
        long suspicious = in.stream().mapToLong(Bucket::suspicious).sum();
        if (suspicious > 0) {
            Map<String, Long> byType = new TreeMap<>();
            in.forEach(x -> ctx.metrics().suspiciousByType().forEach((type, points) -> byType.merge(type, round(points.get(x.t() + b)), Long::sum)));
            causes.add(new Cause("ATTACK", "공격 의심(자동 스캐너 탐색)", "탐색 요청 " + suspicious + "건 — "
                    + byType.entrySet().stream().filter(en -> en.getValue() > 0)
                            .map(en -> SUSPICIOUS_TYPES.getOrDefault(en.getKey(), en.getKey()) + " " + en.getValue() + "건")
                            .collect(Collectors.joining(", "))));
        }
    }

    private static void circuitCauses(List<Bucket> in, Context ctx, List<Cause> causes, long b) {
        ctx.metrics().circuitOpen().forEach((key, points) -> {
            if (in.stream().anyMatch(x -> round(points.get(x.t() + b)) > 0)) {
                causes.add(new Cause("CIRCUIT", "연쇄 차단(차단기 열림)", key + " — 뒤쪽 서비스가 계속 실패해 호출을 잠시 막음"));
            }
        });
    }

    // ── 4) 문장, 요약, 이벤트 ──────────────────────────────────

    /** counts = [5xx, 탐색, 429, 구간 최대 요청] */
    private static String sentence(String kind, long s, long e, List<String> services, long[] counts, List<Cause> causes) {
        long serverErrors = counts[0];
        long suspicious = counts[1];
        long rateLimited = counts[2];
        String when = DAY_TIME.format(Instant.ofEpochMilli(s).atZone(KST)) + "~" + time(e);
        String where = services.isEmpty() ? "" : labels(services) + " 서비스에서 ";
        String what = switch (kind) {
            case "ATTACK" -> "공격 의심 요청 " + suspicious + "건";
            case "TRAFFIC" -> rateLimited > 0 ? "요청 제한 초과 " + rateLimited + "건" : "요청 급증(구간 최대 " + counts[3] + "건)";
            default -> serverErrors > 0 ? "응답 실패 " + serverErrors + "건" : "오류 신호";
        };
        String cause = causes.stream().limit(2).map(Cause::label).collect(Collectors.joining(", "));
        return when + " " + where + what + ". 원인: " + cause + ".";
    }

    private static Summary summary(List<Bucket> buckets, List<Issue> issues, Instant start, Instant stop) {
        long requests = buckets.stream().mapToLong(Bucket::requests).sum();
        long serverErrors = buckets.stream().mapToLong(Bucket::serverErrors).sum();
        long suspicious = buckets.stream().mapToLong(Bucket::suspicious).sum();
        long rateLimited = buckets.stream().mapToLong(Bucket::rateLimited).sum();
        int intended = (int) issues.stream().filter(Issue::intended).count();
        String period = DAY_TIME.format(start.atZone(KST)) + " ~ " + DAY_TIME.format(stop.atZone(KST));
        String headline = issues.isEmpty()
                ? period + " 동안 이상 없음. 요청 " + requests + "건이 모두 정상 처리됐습니다."
                : period + " 동안 이슈 " + issues.size() + "건(실제 장애 " + (issues.size() - intended) + "건, 의도한 시험 " + intended
                        + "건). 응답 실패 총 " + serverErrors + "건" + (suspicious > 0 ? ", 공격 의심 요청 " + suspicious + "건" : "") + ".";
        return new Summary(headline, issues.size(), intended, requests, serverErrors, suspicious, rateLimited);
    }

    private static List<Event> events(Context ctx, Instant start, Instant stop) {
        long s = start.toEpochMilli();
        long e = stop.toEpochMilli();
        List<Event> events = new ArrayList<>();
        for (ChaosEvent c : ctx.chaos()) {
            events.add(new Event(c.getKilledAt().atZone(KST).toInstant().toEpochMilli(), "CHAOS_KILL", c.getServiceName(),
                    "관리자가 파드 " + c.deletedPodList().size() + "개 강제 종료(" + c.getScope() + ")"));
            if (c.getRecoveredAt() != null) {
                events.add(new Event(c.getRecoveredAt().atZone(KST).toInstant().toEpochMilli(), "CHAOS_RECOVER", c.getServiceName(),
                        "복구 완료(" + c.recoverySeconds() + "초)"));
            }
        }
        ctx.pods().stream().filter(p -> p.createdMs() >= s && p.createdMs() < e)
                .forEach(p -> events.add(new Event(p.createdMs(), "POD_START", p.service(), p.pod() + " 시작")));
        Set<String> chaosPods = ctx.chaos().stream().flatMap(c -> c.deletedPodList().stream()).collect(Collectors.toSet());
        ctx.pods().stream().filter(p -> stoppedIn(p, s, e)).forEach(p -> {
            String reason = stopReason(p, ctx, chaosPods);
            events.add(new Event(p.lastSeenMs(), "POD_STOP", p.service(), p.pod() + " 종료(" + (reason == null ? "원인 미상" : reason) + ")"));
        });
        ctx.metrics().restartsByPod().forEach((pod, points) -> points.forEach((t, v) -> {
            if (round(v) > 0 && serviceOf(pod) != null) {
                events.add(new Event(t, "POD_RESTART", serviceOf(pod), pod + " 재시작 " + round(v) + "회"
                        + (ctx.oomPods().contains(pod) ? "(OOMKilled)" : "")));
            }
        }));
        ctx.rollouts().forEach(r -> events.add(new Event(r.t(), "DEPLOY", r.service(), "새 버전 배포 시작")));
        ctx.loadTests().forEach(l -> {
            events.add(new Event(l.startMs(), "LOADTEST_START", null, "부하테스트 시작"));
            events.add(new Event(l.endMs(), "LOADTEST_END", null, "부하테스트 종료(최대 " + l.peakVus() + "명)"));
        });
        ctx.metrics().circuitOpen().forEach((key, points) -> points.entrySet().stream().filter(p -> round(p.getValue()) > 0)
                .map(Map.Entry::getKey).min(Long::compare)
                .ifPresent(t -> events.add(new Event(t, "CIRCUIT_OPEN", key.split(", ")[0], key + " 차단기 열림"))));
        return events.stream().filter(ev -> ev.t() >= s && ev.t() <= e).sorted(Comparator.comparingLong(Event::t)).limit(MAX_EVENTS).toList();
    }

    static String label(String service) {
        return LABELS.getOrDefault(service, service);
    }

    private static String labels(Collection<String> services) {
        return services.stream().map(IncidentReportService::label).collect(Collectors.joining(", "));
    }

    private static String time(long ms) {
        return TIME.format(Instant.ofEpochMilli(ms).atZone(KST));
    }
}
