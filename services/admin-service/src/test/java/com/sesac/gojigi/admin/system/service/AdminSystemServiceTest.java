package com.sesac.gojigi.admin.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sesac.gojigi.admin.system.client.KubernetesClient;
import com.sesac.gojigi.admin.system.client.KubernetesClient.Pod;
import com.sesac.gojigi.admin.system.client.PrometheusClient;
import com.sesac.gojigi.admin.system.dto.request.KillRequest;
import com.sesac.gojigi.admin.system.dto.request.ScenarioRequest;
import com.sesac.gojigi.admin.system.dto.response.SystemResponses;
import com.sesac.gojigi.admin.system.entity.ChaosEvent;
import com.sesac.gojigi.admin.system.entity.KillScope;
import com.sesac.gojigi.admin.system.exception.SystemErrorCode;
import com.sesac.gojigi.admin.system.exception.SystemException;
import com.sesac.gojigi.admin.system.repository.ChaosEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 내리기 규칙 중 통합 테스트로 만들기 어려운 것 — 꺼짐 · 1분 지난 뒤 복구 중 · 일부만 지우고 실패 */
class AdminSystemServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-11-09T05:03:12Z");

    private final KubernetesClient kubernetes = mock(KubernetesClient.class);
    private final ChaosEventRepository repository = mock(ChaosEventRepository.class);
    private final PrometheusClient prometheus = mock(PrometheusClient.class);
    private final Clock clock = Clock.fixed(NOW, KST);

    private AdminSystemService service(boolean enabled) {
        when(repository.save(any(ChaosEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        return new AdminSystemService(kubernetes, prometheus, repository, clock, enabled);
    }

    private static Pod pod(String name, boolean ready) {
        return new Pod(name, "order-service", "node-1", "Running", ready, 0, NOW.minusSeconds(60), false);
    }

    private static SystemResponses.ServiceMetric metric(SystemResponses.Metrics metrics, String name) {
        return metrics.services().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("지표 — 라우트를 서비스로 더해 비율, 요청 0 이면 errorRate null, 라우트 없는 서비스는 둘 다 null · 꺼져 있어도 조회")
    void metrics_aggregates() {
        AdminSystemService service = service(false);
        when(prometheus.byRoute(AdminSystemService.REQUESTS_QUERY)).thenReturn(Optional.of(Map.of(
                "order-service", 4.0, "user-auth", 1.0, "user-auth-strict", 0.5, "user-service", 2.5, "review-service", 0.0)));
        when(prometheus.byRoute(AdminSystemService.ERRORS_QUERY)).thenReturn(Optional.of(Map.of("order-service", 1.0, "user-auth", 0.4)));

        SystemResponses.Metrics metrics = service.metrics();

        assertThat(metrics.services()).hasSize(13);
        assertThat(metric(metrics, "order-service")).isEqualTo(new SystemResponses.ServiceMetric("order-service", 4.0, 0.25));
        assertThat(metric(metrics, "user-service")).isEqualTo(new SystemResponses.ServiceMetric("user-service", 4.0, 0.1));
        assertThat(metric(metrics, "review-service")).isEqualTo(new SystemResponses.ServiceMetric("review-service", 0.0, null));
        assertThat(metric(metrics, "chat-service")).isEqualTo(new SystemResponses.ServiceMetric("chat-service", 0.0, null));
        assertThat(metric(metrics, "gateway-service")).isEqualTo(new SystemResponses.ServiceMetric("gateway-service", null, null));
        verifyNoInteractions(kubernetes);
    }

    @Test
    @DisplayName("지표 — 요청량 조회 실패면 오류 쿼리는 부르지 않고 전부 null, 오류 쿼리만 실패해도 전부 null")
    void metrics_prometheusDown_allNull() {
        AdminSystemService service = service(true);
        when(prometheus.byRoute(AdminSystemService.REQUESTS_QUERY)).thenReturn(Optional.empty());
        assertThat(service.metrics().services()).allSatisfy(m -> {
            assertThat(m.requestsPerSecond()).isNull();
            assertThat(m.errorRate()).isNull();
        });
        verify(prometheus, never()).byRoute(AdminSystemService.ERRORS_QUERY);

        when(prometheus.byRoute(AdminSystemService.REQUESTS_QUERY)).thenReturn(Optional.of(Map.of("order-service", 1.0)));
        when(prometheus.byRoute(AdminSystemService.ERRORS_QUERY)).thenReturn(Optional.empty());
        assertThat(metric(service.metrics(), "order-service").requestsPerSecond()).isNull();
    }

    @Test
    @DisplayName("꺼짐 — 403 SYSTEM_CHAOS_DISABLED, 쿠버네티스는 부르지 않음")
    void kill_disabled_forbidden() {
        AdminSystemService service = service(false);
        assertThatThrownBy(() -> service.kill("order-service", new KillRequest(KillScope.ONE, null), 1L))
                .isInstanceOf(SystemException.class)
                .extracting(e -> ((SystemException) e).getErrorCode()).isEqualTo(SystemErrorCode.SYSTEM_CHAOS_DISABLED);
        verifyNoInteractions(kubernetes);
    }

    @Test
    @DisplayName("1분이 지났어도 복구 전(일부만 Ready)이면 409 SYSTEM_KILL_COOLDOWN")
    void kill_recoveringAfterCooldown_conflict() {
        AdminSystemService service = service(true);
        ChaosEvent last = ChaosEvent.of("order-service", KillScope.ONE, null, List.of("order-service-a"), 1L,
                LocalDateTime.ofInstant(NOW.minusSeconds(120), KST));
        when(repository.findFirstByServiceNameOrderByIdDesc("order-service")).thenReturn(Optional.of(last));
        when(kubernetes.pods()).thenReturn(List.of(pod("order-service-b", true), pod("order-service-c", false)));
        when(kubernetes.desiredReplicas()).thenReturn(Map.of("order-service", 2));

        assertThatThrownBy(() -> service.kill("order-service", new KillRequest(KillScope.ONE, null), 1L))
                .extracting(e -> ((SystemException) e).getErrorCode()).isEqualTo(SystemErrorCode.SYSTEM_KILL_COOLDOWN);
        verify(kubernetes, never()).deletePod(any());
    }

    @Test
    @DisplayName("1분이 지나고 복구됐으면 복구 시각을 남기고 다시 내릴 수 있다")
    void kill_afterRecovery_marksAndKills() {
        AdminSystemService service = service(true);
        ChaosEvent last = ChaosEvent.of("order-service", KillScope.ONE, null, List.of("order-service-a"), 1L,
                LocalDateTime.ofInstant(NOW.minusSeconds(120), KST));
        when(repository.findFirstByServiceNameOrderByIdDesc("order-service")).thenReturn(Optional.of(last));
        when(kubernetes.pods()).thenReturn(List.of(pod("order-service-b", true), pod("order-service-c", true)));
        when(kubernetes.desiredReplicas()).thenReturn(Map.of("order-service", 2));

        var killed = service.kill("order-service", new KillRequest(KillScope.ONE, "  리허설  "), 1L);

        assertThat(killed.deletedPods()).containsExactly("order-service-b");
        assertThat(last.recoverySeconds()).isEqualTo(120L);
        ArgumentCaptor<ChaosEvent> saved = ArgumentCaptor.forClass(ChaosEvent.class);
        verify(repository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getValue().getReason()).isEqualTo("리허설");
    }

    @Test
    @DisplayName("ALL 에서 두 번째 파드 삭제가 실패하면 — 지운 파드만 기록하고 503 을 그대로 던진다")
    void kill_partialFailure_recordsDeleted() {
        AdminSystemService service = service(true);
        when(repository.findFirstByServiceNameOrderByIdDesc("order-service")).thenReturn(Optional.empty());
        when(kubernetes.pods()).thenReturn(List.of(pod("order-service-a", true), pod("order-service-b", true)));
        when(kubernetes.desiredReplicas()).thenReturn(Map.of("order-service", 2));
        doThrow(new SystemException(SystemErrorCode.SYSTEM_CLUSTER_UNAVAILABLE)).when(kubernetes).deletePod("order-service-b");

        assertThatThrownBy(() -> service.kill("order-service", new KillRequest(KillScope.ALL, " "), 7L))
                .extracting(e -> ((SystemException) e).getErrorCode()).isEqualTo(SystemErrorCode.SYSTEM_CLUSTER_UNAVAILABLE);
        ArgumentCaptor<ChaosEvent> saved = ArgumentCaptor.forClass(ChaosEvent.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().deletedPodList()).containsExactly("order-service-a");
        assertThat(saved.getValue().getReason()).isNull();
        assertThat(saved.getValue().getActorId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("첫 삭제부터 실패하면 기록하지 않는다")
    void kill_firstDeleteFails_noRecord() {
        AdminSystemService service = service(true);
        when(repository.findFirstByServiceNameOrderByIdDesc("order-service")).thenReturn(Optional.empty());
        when(kubernetes.pods()).thenReturn(List.of(pod("order-service-a", true)));
        when(kubernetes.desiredReplicas()).thenReturn(Map.of("order-service", 1));
        doThrow(new SystemException(SystemErrorCode.SYSTEM_CLUSTER_UNAVAILABLE)).when(kubernetes).deletePod("order-service-a");

        assertThatThrownBy(() -> service.kill("order-service", new KillRequest(KillScope.ONE, null), 1L))
                .isInstanceOf(SystemException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("시나리오 꺼짐 — 403 SYSTEM_CHAOS_DISABLED, 쿠버네티스는 부르지 않음")
    void scenario_disabled_forbidden() {
        AdminSystemService service = service(false);
        assertThatThrownBy(() -> service.killOneEach(null, 1L))
                .extracting(e -> ((SystemException) e).getErrorCode()).isEqualTo(SystemErrorCode.SYSTEM_CHAOS_DISABLED);
        verifyNoInteractions(kubernetes);
    }

    @Test
    @DisplayName("시나리오 중간 삭제 실패 — 앞 서비스 · 지운 만큼은 기록하고 503 을 그대로 던짐, 뒤 서비스는 안 지움 · 사유는 공백 제거")
    void scenario_partialFailure_recordsDeleted() {
        AdminSystemService service = service(true);
        when(kubernetes.desiredReplicas()).thenReturn(Map.of("order-service", 2, "product-service", 2, "payment-service", 2));
        when(kubernetes.pods()).thenReturn(List.of(
                pod("order-service-a", true), pod("order-service-b", true),
                new Pod("product-service-a", "product-service", "node-1", "Running", true, 0, NOW.minusSeconds(60), false),
                new Pod("product-service-b", "product-service", "node-2", "Running", true, 0, NOW.minusSeconds(60), false),
                new Pod("payment-service-a", "payment-service", "node-1", "Running", true, 0, NOW.minusSeconds(60), false),
                new Pod("payment-service-b", "payment-service", "node-2", "Running", true, 0, NOW.minusSeconds(60), false)));
        when(repository.findFirstByServiceNameOrderByIdDesc(any())).thenReturn(Optional.empty());
        doThrow(new SystemException(SystemErrorCode.SYSTEM_CLUSTER_UNAVAILABLE)).when(kubernetes).deletePod("product-service-a");

        assertThatThrownBy(() -> service.killOneEach(new ScenarioRequest("  리허설  "), 1L))
                .extracting(e -> ((SystemException) e).getErrorCode()).isEqualTo(SystemErrorCode.SYSTEM_CLUSTER_UNAVAILABLE);

        ArgumentCaptor<ChaosEvent> saved = ArgumentCaptor.forClass(ChaosEvent.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getServiceName()).isEqualTo("order-service");
        assertThat(saved.getValue().getReason()).isEqualTo("리허설");
        verify(kubernetes, never()).deletePod("payment-service-a");
    }
}
