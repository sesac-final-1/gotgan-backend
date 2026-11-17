package com.sesac.gojigi.admin.system.service;

import static com.sesac.gojigi.admin.system.client.InClusterKubernetesClient.LOADTEST_DURATION;
import static com.sesac.gojigi.admin.system.client.InClusterKubernetesClient.LOADTEST_ID;
import static com.sesac.gojigi.admin.system.client.InClusterKubernetesClient.LOADTEST_KIND;
import static com.sesac.gojigi.admin.system.client.InClusterKubernetesClient.LOADTEST_SCENARIO;
import static com.sesac.gojigi.admin.system.client.InClusterKubernetesClient.LOADTEST_VUS;

import com.sesac.gojigi.admin.system.client.K6Client;
import com.sesac.gojigi.admin.system.client.KubernetesClient;
import com.sesac.gojigi.admin.system.client.KubernetesClient.LoadJob;
import com.sesac.gojigi.admin.system.client.PrometheusClient;
import com.sesac.gojigi.admin.system.client.PrometheusClient.Sample;
import com.sesac.gojigi.admin.system.dto.request.LoadTestRequests;
import com.sesac.gojigi.admin.system.dto.response.LoadTestResponses;
import com.sesac.gojigi.admin.system.dto.response.LoadTestResponses.Failure;
import com.sesac.gojigi.admin.system.dto.response.LoadTestResponses.Result;
import com.sesac.gojigi.admin.system.dto.response.LoadTestResponses.Run;
import com.sesac.gojigi.admin.system.dto.response.LoadTestResponses.RunState;
import com.sesac.gojigi.admin.system.dto.response.LoadTestResponses.Series;
import com.sesac.gojigi.admin.system.exception.SystemErrorCode;
import com.sesac.gojigi.admin.system.exception.SystemException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 관리자 화면 부하테스트(11/16) — k6 Job 을 만들어 클러스터 안 게이트웨이로 부하를 보낸다.
 * k6 는 지표를 Prometheus 로 push(remote write, 1초)하고, 그래프는 testid 태그로 범위 조회한다.
 * 실행 중 사용자 수는 k6 REST API(externally-controlled)로 바꾼다. 관리자 파드가 1개라 시작 · 계정 준비는 synchronized
 */
@Slf4j
@Service
public class LoadTestService {

    static final int MAX_VUS = 300;
    static final Duration WINDOW = Duration.ofMinutes(5);
    static final Duration DONE_WINDOW = Duration.ofMinutes(15);
    /** 그래프 점 간격 — k6 가 1초마다 push 해서 시연에서 바로 반응하게(11/16 피드백) */
    static final Duration STEP = Duration.ofSeconds(1);
    /** rate 구간 — 1초 push 기준 5개 점. 짧을수록 장애 · 복구가 빨리 보인다 */
    static final String RATE_WINDOW = "[5s]";
    static final int ACCOUNT_COUNT = 300;
    static final String KIND_LOAD = "load";
    static final String KIND_ACCOUNTS = "accounts";

    private final KubernetesClient kubernetes;
    private final PrometheusClient prometheus;
    private final K6Client k6;
    private final Clock clock;
    private final boolean enabled;
    private final String image;
    private final String targetUrl;
    private final String remoteWriteUrl;
    private final String namespace;

    public LoadTestService(KubernetesClient kubernetes, PrometheusClient prometheus, K6Client k6, Clock clock,
            @Value("${app.loadtest.enabled:false}") boolean enabled,
            @Value("${app.loadtest.image}") String image,
            @Value("${app.loadtest.target-url}") String targetUrl,
            @Value("${app.loadtest.remote-write-url}") String remoteWriteUrl,
            @Value("${app.chaos.namespace}") String namespace) {
        this.kubernetes = kubernetes;
        this.prometheus = prometheus;
        this.k6 = k6;
        this.clock = clock;
        this.enabled = enabled;
        this.image = image;
        this.targetUrl = targetUrl;
        this.remoteWriteUrl = remoteWriteUrl;
        this.namespace = namespace;
    }

    /**
     * 최근 부하 실행, 계정 준비 상태 + 부하 그래프 + 결과 — 쿠버네티스 조회 실패면 503, Prometheus 실패면 빈 그래프와 0 결과.
     * 끝난 실행은 그래프를 끝난 시각에서 멈추고(지금까지 흘러가 '-' 로 비지 않게) 결과를 계속 보여 준다(Job 은 24시간 보관)
     */
    public LoadTestResponses.Status status() {
        List<LoadJob> jobs = kubernetes.loadJobs();
        Optional<LoadJob> load = latest(jobs, KIND_LOAD);
        Optional<LoadJob> accounts = latest(jobs, KIND_ACCOUNTS);
        Instant now = clock.instant();
        Instant end = load.map(j -> j.active() > 0 ? now : finishedAt(j, now)).orElse(now);
        Series series = load.map(j -> series(j, end)).orElse(Series.EMPTY);
        Result result = load.map(j -> result(j, end)).orElse(null);
        return new LoadTestResponses.Status(enabled, MAX_VUS, load.map(j -> run(j, j.active() > 0 ? null : end)).orElse(null),
                accounts.map(j -> run(j, null)).orElse(null), series, result);
    }

    /** 끝난 실행의 실제 끝 시각 — k6 지표가 마지막으로 들어온 시각, 없으면 시작 + 최대 시간 */
    private Instant finishedAt(LoadJob job, Instant now) {
        long span = Math.max(60, Duration.between(job.createdAt(), now).toSeconds() + 60);
        Instant planned = job.createdAt().plusSeconds(job.durationSec());
        return prometheus.vector("max(max_over_time(timestamp(k6_vus{testid=\"" + job.id() + "\"})[" + span + "s:5s]))")
                .flatMap(s -> s.stream().findFirst()).map(s -> Instant.ofEpochMilli(Math.round(s.value() * 1000)))
                .filter(t -> t.isAfter(job.createdAt())).orElse(planned.isBefore(now) ? planned : now);
    }

    /** 누적 결과 — end 시각 기준, 시작부터의 구간. 실행 중이면 지금까지 값 */
    private Result result(LoadJob job, Instant end) {
        long elapsed = Math.max(1, Duration.between(job.createdAt(), end).toSeconds());
        String t = "testid=\"" + job.id() + "\"";
        String w = "[" + (elapsed + 30) + "s]";
        Map<String, Long> byStatus = new TreeMap<>();
        Map<String, Long> failures = new HashMap<>();
        long requests = 0;
        for (PrometheusClient.Series s : prometheus.vector("sum by (name, status)(last_over_time(k6_http_reqs_total{" + t + "}" + w + "))", end)
                .orElse(List.of())) {
            long n = Math.round(s.value());
            String status = s.labels().getOrDefault("status", "");
            requests += n;
            byStatus.merge(status.isEmpty() || "0".equals(status) ? "연결 실패" : status, n, Long::sum);
            if (!status.startsWith("2")) {
                failures.merge(s.labels().getOrDefault("name", "-") + "	" + status, n, Long::sum);
            }
        }
        long failed = failures.values().stream().mapToLong(Long::longValue).sum();
        List<Failure> top = failures.entrySet().stream().filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(5)
                .map(e -> new Failure(e.getKey().split("	")[0], e.getKey().split("	")[1], e.getValue())).toList();
        Double p95 = prometheus.vector("histogram_quantile(0.95, sum(increase(k6_http_req_duration_seconds{" + t + "}" + w + ")))", end)
                .flatMap(s -> s.stream().findFirst()).map(s -> Math.round(s.value() * 10000) / 10.0).orElse(null);
        int peak = prometheus.vector("max_over_time(sum(k6_vus{" + t + "})" + "[" + (elapsed + 30) + "s:5s])", end)
                .flatMap(s -> s.stream().findFirst()).map(s -> (int) Math.round(s.value())).orElse(0);
        Map<String, Long> serverErrors = new TreeMap<>();
        prometheus.vector("sum by (routeId)(increase(spring_cloud_gateway_requests_seconds_count{httpStatusCode=~\"5..\"}" + w + "))", end)
                .orElse(List.of()).forEach(s -> {
                    long n = Math.round(s.value());
                    String route = s.labels().getOrDefault("routeId", "");
                    if (n > 0) {
                        serverErrors.merge(SystemCatalog.ROUTE_SERVICES.getOrDefault(route, route), n, Long::sum);
                    }
                });
        return new Result(requests, failed, byStatus, p95, Math.round(requests * 10.0 / elapsed) / 10.0, peak, elapsed, top, serverErrors);
    }

    /** 시작 — 꺼짐 403, 이미 실행 중(부하 · 계정 준비 포함) 409 */
    public synchronized LoadTestResponses.Started start(LoadTestRequests.Start request) {
        requireEnabled();
        requireIdle();
        String id = newId();
        Map<String, String> env = new LinkedHashMap<>();
        env.put("SCENARIO", request.scenario().script());
        env.put("VUS", String.valueOf(request.vus()));
        env.put("MAX_VUS", String.valueOf(MAX_VUS));
        env.put("DURATION", request.durationSec() + "s");
        kubernetes.createJob(job(id, KIND_LOAD, "demo.js", request.scenario().name(), request.vus(), request.durationSec(), env));
        log.info("부하테스트 시작 - id={}, scenario={}, vus={}, durationSec={}", id, request.scenario(), request.vus(), request.durationSec());
        return new LoadTestResponses.Started(id);
    }

    /** 계정 준비 — 부하용 계정 300개 가입(이미 있으면 그대로). 장바구니 · 혼합 시나리오 전에 하루 한 번 */
    public synchronized LoadTestResponses.Started prepareAccounts() {
        requireEnabled();
        requireIdle();
        String id = newId();
        kubernetes.createJob(job(id, KIND_ACCOUNTS, "accounts.js", null, 6, 300, Map.of("COUNT", String.valueOf(ACCOUNT_COUNT))));
        log.info("부하용 계정 준비 시작 - id={}", id);
        return new LoadTestResponses.Started(id);
    }

    /** 실행 중 사용자 수 변경 — 실행 중인 부하가 없거나 파드가 아직 뜨지 않았으면 409, k6 연결 실패 503 */
    public void scale(LoadTestRequests.Scale request) {
        requireEnabled();
        LoadJob running = latest(kubernetes.loadJobs(), KIND_LOAD).filter(j -> j.active() > 0)
                .orElseThrow(() -> new SystemException(SystemErrorCode.SYSTEM_LOADTEST_NOT_RUNNING));
        String podIp = kubernetes.loadJobPodIp(running.id())
                .orElseThrow(() -> new SystemException(SystemErrorCode.SYSTEM_LOADTEST_NOT_RUNNING));
        k6.scale(podIp, request.vus());
        log.info("부하테스트 사용자 수 변경 - id={}, vus={}", running.id(), request.vus());
    }

    /**
     * 중지 — 부하는 k6 에 정상 종료를 보내 Job 을 완료로 남긴다(그래프, 결과가 그대로 보이게, 11/17).
     * 파드가 아직 없거나 k6 에 닿지 않으면, 그리고 계정 준비 Job 은 지금처럼 삭제. 실행 중인 것이 없으면 아무것도 하지 않는다
     */
    public void stop() {
        kubernetes.loadJobs().stream().filter(j -> j.active() > 0).forEach(j -> {
            boolean graceful = KIND_LOAD.equals(j.kind()) && kubernetes.loadJobPodIp(j.id()).map(k6::stop).orElse(false);
            if (!graceful) {
                kubernetes.deleteJob(j.name());
            }
            log.info("부하테스트 중지 - id={}, kind={}, {}", j.id(), j.kind(), graceful ? "정상 종료" : "Job 삭제");
        });
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new SystemException(SystemErrorCode.SYSTEM_LOADTEST_DISABLED);
        }
    }

    private void requireIdle() {
        if (kubernetes.loadJobs().stream().anyMatch(j -> j.active() > 0)) {
            throw new SystemException(SystemErrorCode.SYSTEM_LOADTEST_RUNNING);
        }
    }

    /** Job 이름 · 라벨 값 — 소문자 · 숫자 · 하이픈만(쿠버네티스 규칙) */
    private String newId() {
        return "lt-" + clock.instant().getEpochSecond();
    }

    private static Optional<LoadJob> latest(List<LoadJob> jobs, String kind) {
        return jobs.stream().filter(j -> kind.equals(j.kind()) && j.createdAt() != null).max(Comparator.comparing(LoadJob::createdAt));
    }

    private Run run(LoadJob job, Instant finished) {
        RunState state = RunState.DONE;
        if (job.active() > 0) {
            state = RunState.RUNNING;
        } else if (job.failed() > 0) {
            state = RunState.FAILED;
        }
        return new Run(job.id(), job.scenario(), state, job.vus(), job.durationSec(), OffsetDateTime.ofInstant(job.createdAt(), clock.getZone()),
                OffsetDateTime.ofInstant(job.createdAt().plusSeconds(job.durationSec()), clock.getZone()),
                finished == null ? null : OffsetDateTime.ofInstant(finished, clock.getZone()));
    }

    /** 실행 중은 최근 5분, 끝났으면 그 실행 전체(최대 15분)를 끝난 시각에서 멈춰서, 1초 간격 — testid 태그로 이 실행만 */
    private Series series(LoadJob job, Instant end) {
        Duration window = job.active() > 0 ? WINDOW : DONE_WINDOW;
        Instant start = job.createdAt().isAfter(end.minus(window)) ? job.createdAt() : end.minus(window);
        String t = "testid=\"" + job.id() + "\"";
        String requests = "sum(rate(k6_http_reqs_total{" + t + "}" + RATE_WINDOW + "))";
        return new Series(
                range("sum(k6_vus{" + t + "})", start, end, UnaryOperator.identity()),
                range(requests, start, end, UnaryOperator.identity()),
                // p95 는 네이티브 히스토그램으로 최근 5초 구간만 — k6 의 p(95) 추세 값은 테스트 전체 누적이라 한 번 튀면 내려오지 않는다
                range("histogram_quantile(0.95, sum(rate(k6_http_req_duration_seconds{" + t + "}" + RATE_WINDOW + ")))", start, end, v -> v * 1000),   // 초 → ms
                range("(sum(rate(k6_http_reqs_total{" + t + ",status!~\"2..\"}" + RATE_WINDOW + ")) or vector(0)) / " + requests, start, end,
                        UnaryOperator.identity()));
    }

    private List<Sample> range(String promql, Instant start, Instant end, UnaryOperator<Double> scale) {
        return prometheus.range(promql, start, end, STEP).orElse(List.of()).stream()
                .map(s -> new Sample(s.t(), scale.apply(s.v()))).toList();
    }

    /**
     * k6 Job — 앱 노드, 파드 라벨 tier=backend(NetworkPolicy: 게이트웨이 · admin 과 통신). app 라벨은 붙이지 않는다(상태 화면 집계 제외).
     * 비밀번호는 loadtest-secret(없어도 시작 — 로그인 시나리오만 실패). 끝나면 5분 뒤 자동 삭제, 최대 시간 + 2분이 지나면 강제 종료
     */
    Map<String, Object> job(String id, String kind, String script, String scenario, int vus, int durationSec, Map<String, String> extraEnv) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("BASE_URL", targetUrl);
        env.put("K6_PROMETHEUS_RW_SERVER_URL", remoteWriteUrl);
        env.put("K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM", "true");   // 응답 시간을 히스토그램으로 — 구간 p95 계산용
        env.put("K6_PROMETHEUS_RW_PUSH_INTERVAL", "1s");
        env.putAll(extraEnv);

        Map<String, String> labels = Map.of(LOADTEST_ID, id, LOADTEST_KIND, kind, "tier", "backend");
        Map<String, String> annotations = new LinkedHashMap<>();
        annotations.put(LOADTEST_VUS, String.valueOf(vus));
        annotations.put(LOADTEST_DURATION, String.valueOf(durationSec));
        if (scenario != null) {
            annotations.put(LOADTEST_SCENARIO, scenario);
        }

        Map<String, Object> container = new LinkedHashMap<>();
        container.put("name", "k6");
        container.put("image", image);
        container.put("args", List.of("run", "--address", "0.0.0.0:6565", "-o", "experimental-prometheus-rw", "--tag", "testid=" + id,
                "/scripts/" + script));
        container.put("env", env.entrySet().stream().map(e -> Map.of("name", e.getKey(), "value", e.getValue())).toList());
        container.put("envFrom", List.of(Map.of("secretRef", Map.of("name", "loadtest-secret", "optional", true))));
        container.put("ports", List.of(Map.of("containerPort", 6565)));
        container.put("resources", Map.of("requests", Map.of("cpu", "500m", "memory", "512Mi"), "limits", Map.of("cpu", "1", "memory", "1Gi")));
        container.put("volumeMounts", List.of(Map.of("name", "scripts", "mountPath", "/scripts")));

        Map<String, Object> podSpec = new LinkedHashMap<>();
        podSpec.put("restartPolicy", "Never");
        podSpec.put("nodeSelector", Map.of("tier", "app"));
        podSpec.put("automountServiceAccountToken", false);
        podSpec.put("containers", List.of(container));
        podSpec.put("volumes", List.of(Map.of("name", "scripts", "configMap", Map.of("name", "loadtest-scripts"))));

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("backoffLimit", 0);
        spec.put("activeDeadlineSeconds", durationSec + 120);
        spec.put("ttlSecondsAfterFinished", 86_400);   // 끝난 뒤 24시간 보관 — 결과 화면이 마지막 실행을 계속 보여 준다(11/17)
        spec.put("template", Map.of("metadata", Map.of("labels", labels), "spec", podSpec));

        return Map.of("apiVersion", "batch/v1", "kind", "Job",
                "metadata", Map.of("name", "loadtest-" + id, "namespace", namespace, "labels", labels, "annotations", annotations),
                "spec", spec);
    }
}
