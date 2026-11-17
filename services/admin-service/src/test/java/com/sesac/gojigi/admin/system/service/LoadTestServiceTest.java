package com.sesac.gojigi.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sesac.gojigi.admin.system.client.K6Client;
import com.sesac.gojigi.admin.system.client.KubernetesClient;
import com.sesac.gojigi.admin.system.client.KubernetesClient.LoadJob;
import com.sesac.gojigi.admin.system.client.PrometheusClient;
import com.sesac.gojigi.admin.system.client.PrometheusClient.Sample;
import com.sesac.gojigi.admin.system.dto.request.LoadTestRequests;
import com.sesac.gojigi.admin.system.dto.response.LoadTestResponses;
import com.sesac.gojigi.admin.system.dto.response.LoadTestResponses.RunState;
import com.sesac.gojigi.admin.system.entity.LoadTestScenario;
import com.sesac.gojigi.admin.system.exception.SystemErrorCode;
import com.sesac.gojigi.admin.system.exception.SystemException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 관리자 화면 부하테스트 — 쿠버네티스, Prometheus, k6 는 가짜. Job 본문, 상태 계산, 그래프 쿼리, 실행 중 제한 */
class LoadTestServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-16T06:00:00Z");

    private final KubernetesClient kubernetes = mock(KubernetesClient.class);
    private final PrometheusClient prometheus = mock(PrometheusClient.class);
    private final K6Client k6 = mock(K6Client.class);
    private final Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));

    private LoadTestService service(boolean enabled) {
        return new LoadTestService(kubernetes, prometheus, k6, clock, enabled, "grafana/k6:1.8.3",
                "http://gateway-service.sesac-gojigi.svc:443", "http://prometheus.monitoring:9090/api/v1/write", "sesac-gojigi");
    }

    private static LoadJob job(String id, String kind, Instant created, int active, int succeeded, int failed) {
        return new LoadJob("loadtest-" + id, id, kind, "load".equals(kind) ? "BROWSE" : null, 50, 300, created, active, succeeded, failed);
    }

    @BeforeEach
    void noJobs() {
        when(kubernetes.loadJobs()).thenReturn(List.of());
    }

    @Test
    @DisplayName("시작 — Job 이름, 라벨(app 없음, tier=backend), 시나리오 환경 변수, k6 인자, Secret, 자동 정리")
    @SuppressWarnings("unchecked")
    void start_createsJob() {
        LoadTestResponses.Started started = service(true).start(new LoadTestRequests.Start(LoadTestScenario.REVIEWS, 20, 120));

        assertThat(started.id()).isEqualTo("lt-" + NOW.getEpochSecond());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(kubernetes).createJob(captor.capture());
        Map<String, Object> job = captor.getValue();
        Map<String, Object> meta = (Map<String, Object>) job.get("metadata");
        assertThat(meta).containsEntry("name", "loadtest-" + started.id()).containsEntry("namespace", "sesac-gojigi");
        assertThat((Map<String, String>) meta.get("labels")).containsEntry("gotgan.loadtest/kind", "load").containsEntry("tier", "backend")
                .doesNotContainKey("app");
        assertThat((Map<String, String>) meta.get("annotations")).containsEntry("gotgan.loadtest/scenario", "REVIEWS")
                .containsEntry("gotgan.loadtest/vus", "20").containsEntry("gotgan.loadtest/duration-sec", "120");

        Map<String, Object> spec = (Map<String, Object>) job.get("spec");
        assertThat(spec).containsEntry("activeDeadlineSeconds", 240).containsEntry("ttlSecondsAfterFinished", 86_400).containsEntry("backoffLimit", 0);
        Map<String, Object> podSpec = (Map<String, Object>) ((Map<String, Object>) spec.get("template")).get("spec");
        Map<String, Object> container = ((List<Map<String, Object>>) podSpec.get("containers")).get(0);
        assertThat(container).containsEntry("image", "grafana/k6:1.8.3");
        assertThat((List<String>) container.get("args")).contains("testid=" + started.id(), "/scripts/demo.js", "experimental-prometheus-rw");
        assertThat((List<Map<String, String>>) container.get("env")).contains(
                Map.of("name", "SCENARIO", "value", "reviews"), Map.of("name", "VUS", "value", "20"),
                Map.of("name", "MAX_VUS", "value", "300"), Map.of("name", "DURATION", "value", "120s"),
                Map.of("name", "BASE_URL", "value", "http://gateway-service.sesac-gojigi.svc:443"));
        assertThat(container.get("envFrom").toString()).contains("loadtest-secret");
    }

    @Test
    @DisplayName("계정 준비 — accounts.js, COUNT 300, kind accounts")
    @SuppressWarnings("unchecked")
    void prepareAccounts_createsJob() {
        service(true).prepareAccounts();

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(kubernetes).createJob(captor.capture());
        Map<String, Object> spec = (Map<String, Object>) captor.getValue().get("spec");
        Map<String, Object> podSpec = (Map<String, Object>) ((Map<String, Object>) spec.get("template")).get("spec");
        Map<String, Object> container = ((List<Map<String, Object>>) podSpec.get("containers")).get(0);
        assertThat((List<String>) container.get("args")).contains("/scripts/accounts.js");
        assertThat((List<Map<String, String>>) container.get("env")).contains(Map.of("name", "COUNT", "value", "300"));
        assertThat(captor.getValue().toString()).contains("gotgan.loadtest/kind=accounts");
        assertThat((Map<String, String>) ((Map<String, Object>) captor.getValue().get("metadata")).get("annotations"))
                .doesNotContainKey("gotgan.loadtest/scenario");
    }

    @Test
    @DisplayName("꺼져 있으면 시작, 계정 준비, 사용자 수 변경 403, 중지는 된다")
    void disabled() {
        LoadTestService service = service(false);
        assertCode(() -> service.start(new LoadTestRequests.Start(LoadTestScenario.BROWSE, 10, 60)), SystemErrorCode.SYSTEM_LOADTEST_DISABLED);
        assertCode(service::prepareAccounts, SystemErrorCode.SYSTEM_LOADTEST_DISABLED);
        assertCode(() -> service.scale(new LoadTestRequests.Scale(10)), SystemErrorCode.SYSTEM_LOADTEST_DISABLED);
        service.stop();
        verify(kubernetes, never()).createJob(any());
    }

    @Test
    @DisplayName("이미 실행 중(부하든 계정 준비든)이면 시작, 계정 준비 409")
    void running_conflict() {
        when(kubernetes.loadJobs()).thenReturn(List.of(job("lt-1", "accounts", NOW.minusSeconds(10), 1, 0, 0)));
        LoadTestService service = service(true);
        assertCode(() -> service.start(new LoadTestRequests.Start(LoadTestScenario.BROWSE, 10, 60)), SystemErrorCode.SYSTEM_LOADTEST_RUNNING);
        assertCode(service::prepareAccounts, SystemErrorCode.SYSTEM_LOADTEST_RUNNING);
        verify(kubernetes, never()).createJob(any());
    }

    @Test
    @DisplayName("사용자 수 변경 — 실행 중인 최신 부하 Job 파드 IP 로 k6 호출")
    void scale() {
        when(kubernetes.loadJobs()).thenReturn(List.of(job("lt-1", "load", NOW.minusSeconds(100), 0, 1, 0),
                job("lt-2", "load", NOW.minusSeconds(30), 1, 0, 0)));
        when(kubernetes.loadJobPodIp("lt-2")).thenReturn(Optional.of("10.0.11.171"));

        service(true).scale(new LoadTestRequests.Scale(200));

        verify(k6).scale("10.0.11.171", 200);
    }

    @Test
    @DisplayName("사용자 수 변경 — 실행 중인 부하 없음, 파드 아직 없음 409")
    void scale_notRunning() {
        LoadTestService service = service(true);
        assertCode(() -> service.scale(new LoadTestRequests.Scale(10)), SystemErrorCode.SYSTEM_LOADTEST_NOT_RUNNING);

        when(kubernetes.loadJobs()).thenReturn(List.of(job("lt-2", "load", NOW.minusSeconds(3), 1, 0, 0)));
        when(kubernetes.loadJobPodIp("lt-2")).thenReturn(Optional.empty());
        assertCode(() -> service.scale(new LoadTestRequests.Scale(10)), SystemErrorCode.SYSTEM_LOADTEST_NOT_RUNNING);
        verify(k6, never()).scale(anyString(), eq(10));
    }

    @Test
    @DisplayName("중지 — 실행 중인 Job 만 삭제")
    void stop() {
        when(kubernetes.loadJobs()).thenReturn(List.of(job("lt-1", "load", NOW.minusSeconds(100), 0, 1, 0),
                job("lt-2", "load", NOW.minusSeconds(30), 1, 0, 0)));

        service(true).stop();

        verify(kubernetes).deleteJob("loadtest-lt-2");
        verify(kubernetes, never()).deleteJob("loadtest-lt-1");
    }

    @Test
    @DisplayName("상태 — 최신 부하, 계정 준비, 상태 계산(실행 중, 실패, 완료), 그래프는 testid 쿼리, p95 초 → ms")
    void status() {
        when(kubernetes.loadJobs()).thenReturn(List.of(job("lt-1", "load", NOW.minusSeconds(900), 0, 1, 0),
                job("lt-2", "load", NOW.minusSeconds(60), 1, 0, 0), job("lt-0", "accounts", NOW.minusSeconds(1200), 0, 0, 1)));
        when(prometheus.range(anyString(), any(), any(), any())).thenReturn(Optional.of(List.of(new Sample(1000, 2))));
        when(prometheus.range(contains("k6_http_req_duration_seconds"), any(), any(), any())).thenReturn(Optional.of(List.of(new Sample(1000, 0.095))));

        LoadTestResponses.Status status = service(true).status();

        assertThat(status.enabled()).isTrue();
        assertThat(status.maxVus()).isEqualTo(300);
        assertThat(status.load().id()).isEqualTo("lt-2");
        assertThat(status.load().state()).isEqualTo(RunState.RUNNING);
        assertThat(status.load().scenario()).isEqualTo("BROWSE");
        assertThat(status.accounts().state()).isEqualTo(RunState.FAILED);
        assertThat(status.series().vus()).containsExactly(new Sample(1000, 2));
        assertThat(status.series().p95Ms().get(0).v()).isEqualTo(95.0, org.assertj.core.data.Offset.offset(1e-9));
        // 시작이 5분 안이면 그 시각부터 — 60초 전
        verify(prometheus).range(eq("sum(k6_vus{testid=\"lt-2\"})"), eq(NOW.minusSeconds(60)), eq(NOW), eq(Duration.ofSeconds(1)));
        verify(prometheus).range(contains("status!~\"2..\""), any(), any(), any());
    }

    @Test
    @DisplayName("상태 — 끝난 실행은 그래프를 끝난 시각(지표 없으면 시작 + 최대 시간)에서 멈춤, Prometheus 실패면 빈 그래프, 기록 없으면 null")
    void status_windowAndEmpty() {
        when(kubernetes.loadJobs()).thenReturn(List.of(job("lt-1", "load", NOW.minusSeconds(900), 0, 1, 0)));
        when(prometheus.range(anyString(), any(), any(), any())).thenReturn(Optional.empty());

        LoadTestResponses.Status status = service(true).status();

        assertThat(status.load().state()).isEqualTo(RunState.DONE);
        assertThat(status.accounts()).isNull();
        assertThat(status.series().rps()).isEmpty();
        verify(prometheus).range(eq("sum(k6_vus{testid=\"lt-1\"})"), eq(NOW.minusSeconds(900)), eq(NOW.minusSeconds(600)), any());
        assertThat(status.load().finishedAt().toInstant()).isEqualTo(NOW.minusSeconds(600));
        assertThat(status.load().endsAt().toInstant()).isEqualTo(NOW.minusSeconds(600));
        assertThat(status.result().requests()).isZero();

        when(kubernetes.loadJobs()).thenReturn(List.of());
        assertThat(service(true).status().load()).isNull();
    }

    @Test
    @DisplayName("결과 — 상태 코드별 건수, 실패(2xx 아님 + 연결 실패), 실패 많은 API, p95, 최대 사용자, 같은 시간 게이트웨이 5xx 서비스별")
    void status_result() {
        when(kubernetes.loadJobs()).thenReturn(List.of(job("lt-2", "load", NOW.minusSeconds(400), 0, 1, 0)));
        when(prometheus.range(anyString(), any(), any(), any())).thenReturn(Optional.of(List.of()));
        when(prometheus.vector(contains("timestamp(k6_vus"))).thenReturn(Optional.of(List.of(
                new PrometheusClient.Series(Map.of(), NOW.minusSeconds(100).getEpochSecond()))));
        when(prometheus.vector(contains("k6_http_reqs_total"), any(Instant.class))).thenReturn(Optional.of(List.of(
                new PrometheusClient.Series(Map.of("name", "GET /api/products", "status", "200"), 2900),
                new PrometheusClient.Series(Map.of("name", "GET /api/products", "status", "502"), 7),
                new PrometheusClient.Series(Map.of("name", "POST /api/carts/items", "status", "0"), 3))));
        when(prometheus.vector(contains("histogram_quantile"), any(Instant.class)))
                .thenReturn(Optional.of(List.of(new PrometheusClient.Series(Map.of(), 0.1234))));
        when(prometheus.vector(contains("max_over_time(sum(k6_vus"), any(Instant.class)))
                .thenReturn(Optional.of(List.of(new PrometheusClient.Series(Map.of(), 200))));
        when(prometheus.vector(contains("spring_cloud_gateway_requests_seconds_count"), any(Instant.class))).thenReturn(Optional.of(List.of(
                new PrometheusClient.Series(Map.of("routeId", "product-service"), 6.6), new PrometheusClient.Series(Map.of("routeId", "review-service"), 0.2))));

        LoadTestResponses.Result result = service(true).status().result();

        assertThat(result.requests()).isEqualTo(2910);
        assertThat(result.failed()).isEqualTo(10);
        assertThat(result.byStatus()).containsEntry("200", 2900L).containsEntry("502", 7L).containsEntry("연결 실패", 3L);
        assertThat(result.topFailures()).containsExactly(new LoadTestResponses.Failure("GET /api/products", "502", 7),
                new LoadTestResponses.Failure("POST /api/carts/items", "0", 3));
        assertThat(result.p95Ms()).isEqualTo(123.4);
        assertThat(result.peakVus()).isEqualTo(200);
        assertThat(result.elapsedSec()).isEqualTo(300);
        assertThat(result.avgRps()).isEqualTo(9.7);
        assertThat(result.serverErrors()).containsExactly(Map.entry("product-service", 7L));
    }

    private static void assertCode(Runnable call, SystemErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(SystemException.class)
                .extracting(e -> ((SystemException) e).getErrorCode()).isEqualTo(code);
    }
}
