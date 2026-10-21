package com.sesac.gojigi.common.web.observation;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ActuatorObservationPredicateTest {

    private final ActuatorObservationPredicate predicate = new ActuatorObservationPredicate();

    private static ServerRequestObservationContext request(String uri) {
        return new ServerRequestObservationContext(new MockHttpServletRequest("GET", uri), new MockHttpServletResponse());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/health/readiness", "/actuator/health/liveness", "/actuator/prometheus"})
    @DisplayName("/actuator 요청(프로브 · Prometheus 수집)은 관측하지 않는다")
    void actuator_excluded(String uri) {
        assertThat(predicate.test("http.server.requests", request(uri))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/seller/settlements", "/internal/settlements/1", "/actuators", "/api/actuator/x"})
    @DisplayName("업무 요청과 /actuator 로 시작하지 않는 경로는 그대로 관측한다")
    void business_kept(String uri) {
        assertThat(predicate.test("http.server.requests", request(uri))).isTrue();
    }

    @Test
    @DisplayName("서버 요청이 아닌 관측(스케줄러 · HTTP 클라이언트 등)은 건드리지 않는다")
    void otherContext_kept() {
        assertThat(predicate.test("tasks.scheduled.execution", new Observation.Context())).isTrue();
    }

    @Test
    @DisplayName("레지스트리에 걸면 /actuator 관측은 noop 이 되어 기록되지 않는다")
    void registry_noop() {
        List<String> started = new ArrayList<>();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig()
                .observationPredicate(predicate)
                .observationHandler(new ObservationHandler<>() {   // 트레이싱 핸들러 대신 — 시작된 관측의 경로만 기록
                    @Override
                    public void onStart(Observation.Context context) {
                        started.add(((ServerRequestObservationContext) context).getCarrier().getRequestURI());
                    }

                    @Override
                    public boolean supportsContext(Observation.Context context) {
                        return true;
                    }
                });

        Observation probe = Observation.createNotStarted("http.server.requests", () -> request("/actuator/health/readiness"), registry);
        probe.observe(() -> { });
        Observation.createNotStarted("http.server.requests", () -> request("/api/seller/settlements"), registry).observe(() -> { });

        assertThat(probe.isNoop()).isTrue();
        assertThat(started).containsExactly("/api/seller/settlements");
    }
}
