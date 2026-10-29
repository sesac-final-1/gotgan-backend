package com.sesac.gojigi.admin.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.sesac.gojigi.admin.common.client.RemoteCalls;
import com.sesac.gojigi.admin.dashboard.client.OrderClientAdapter;
import com.sesac.gojigi.admin.product.client.AdminProductClientAdapter;
import com.sesac.gojigi.admin.settlement.client.SettlementClientAdapter;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** 관리자 대시보드 조합 — 네 영역 동시 호출 · 요청 스레드의 Observation(traceId) 전파 · 실패 영역만 unavailable */
class AdminDashboardServiceTest {

    private final SettlementClientAdapter settlement = mock(SettlementClientAdapter.class);
    private final OrderClientAdapter order = mock(OrderClientAdapter.class);
    private final AdminProductClientAdapter product = mock(AdminProductClientAdapter.class);
    private final AdminDashboardService service = new AdminDashboardService(settlement, order, product);

    private static final JsonNode OK = JsonNodeFactory.instance.objectNode().put("ok", true);

    @Test
    @DisplayName("네 영역을 동시에 호출 — 넷이 모두 도착해야 풀리는 래치가 풀림, 요청의 Observation 을 이어받음")
    void callsSectionsInParallelWithObservation() {
        CountDownLatch allStarted = new CountDownLatch(4);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(context -> true);
        List<Observation> seen = new CopyOnWriteArrayList<>();
        Answer<JsonNode> waitForOthers = invocation -> {
            seen.add(registry.getCurrentObservation());
            allStarted.countDown();
            // 순차 호출이면 첫 호출이 나머지 셋을 기다리다 시간 초과
            if (!allStarted.await(2, TimeUnit.SECONDS)) {
                throw RemoteCalls.unavailable("test", new IllegalStateException("순차 호출"));
            }
            return OK;
        };
        given(settlement.platformStats()).willAnswer(waitForOthers);
        given(order.platformStats()).willAnswer(waitForOthers);
        given(order.dailyStats(AdminDashboardService.DAILY_DAYS)).willAnswer(waitForOthers);
        given(product.platformStats()).willAnswer(waitForOthers);

        Observation request = Observation.start("admin.dashboard", registry);
        AdminDashboardService.Dashboard dashboard;
        try (Observation.Scope ignored = request.openScope()) {
            dashboard = service.dashboard();
        } finally {
            request.stop();
        }

        assertThat(dashboard.unavailable()).isEmpty();
        assertThat(List.of(dashboard.settlement(), dashboard.order(), dashboard.orderDaily(), dashboard.product())).containsOnly(OK);
        assertThat(seen).hasSize(4).containsOnly(request);
    }

    @Test
    @DisplayName("실패한 영역만 null + unavailable — 순서는 응답 필드 순서(settlement · order · orderDaily · product)")
    void failedSectionsAreUnavailableInFieldOrder() {
        given(settlement.platformStats()).willThrow(RemoteCalls.unavailable("settlement", new IllegalStateException()));
        given(order.platformStats()).willReturn(OK);
        given(order.dailyStats(AdminDashboardService.DAILY_DAYS)).willReturn(OK);
        given(product.platformStats()).willThrow(RemoteCalls.unavailable("product", new IllegalStateException()));

        AdminDashboardService.Dashboard dashboard = service.dashboard();

        assertThat(dashboard.settlement()).isNull();
        assertThat(dashboard.order()).isEqualTo(OK);
        assertThat(dashboard.product()).isNull();
        assertThat(dashboard.unavailable()).containsExactly("settlement", "product");
    }
}
