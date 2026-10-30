package com.sesac.gojigi.store.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.store.dashboard.client.DashboardClientAdapter;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** 판매자 대시보드 조합 — 네 영역 동시 호출 · 요청 스레드의 Observation(traceId) 전파 · 실패 영역만 unavailable */
class SellerDashboardServiceTest {

    private static final Long STORE_ID = 1L;
    private static final JsonNode OK = JsonNodeFactory.instance.objectNode().put("ok", true);

    private final DashboardClientAdapter adapter = mock(DashboardClientAdapter.class);
    private final SellerDashboardService service = new SellerDashboardService(adapter);

    @Test
    @DisplayName("네 영역을 동시에 호출 — 넷이 모두 도착해야 풀리는 래치가 풀림, 요청의 Observation 을 이어받음")
    void dashboard_callsSectionsInParallel_withObservation() {
        // given
        CountDownLatch allStarted = new CountDownLatch(4);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(context -> true);
        List<Observation> seen = new CopyOnWriteArrayList<>();
        Answer<JsonNode> waitForOthers = invocation -> {
            seen.add(registry.getCurrentObservation());
            allStarted.countDown();
            // 순차 호출이면 첫 호출이 나머지 셋을 기다리다 시간 초과
            if (!allStarted.await(2, TimeUnit.SECONDS)) {
                throw new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR);
            }
            return OK;
        };
        given(adapter.orderStats(STORE_ID)).willAnswer(waitForOthers);
        given(adapter.orderDailyStats(STORE_ID, SellerDashboardService.DAILY_DAYS)).willAnswer(waitForOthers);
        given(adapter.productStats(STORE_ID)).willAnswer(waitForOthers);
        given(adapter.settlementStats(STORE_ID)).willAnswer(waitForOthers);

        // when
        Observation request = Observation.start("seller.dashboard", registry);
        SellerDashboardService.Dashboard dashboard;
        try (Observation.Scope ignored = request.openScope()) {
            dashboard = service.dashboard(STORE_ID);
        } finally {
            request.stop();
        }

        // then
        assertThat(dashboard.unavailable()).isEmpty();
        assertThat(List.of(dashboard.order(), dashboard.orderDaily(), dashboard.product(), dashboard.settlement())).containsOnly(OK);
        assertThat(seen).hasSize(4).containsOnly(request);
    }

    @Test
    @DisplayName("실패한 영역만 null + unavailable — 순서는 응답 필드 순서(order · orderDaily · product · settlement)")
    void dashboard_whenSomeFail_returnsPartial() {
        // given
        given(adapter.orderStats(STORE_ID)).willThrow(new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR));
        given(adapter.orderDailyStats(STORE_ID, SellerDashboardService.DAILY_DAYS))
                .willThrow(new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR));
        given(adapter.productStats(STORE_ID)).willReturn(OK);
        given(adapter.settlementStats(STORE_ID)).willThrow(new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR));

        // when
        SellerDashboardService.Dashboard dashboard = service.dashboard(STORE_ID);

        // then
        assertThat(dashboard.order()).isNull();
        assertThat(dashboard.orderDaily()).isNull();
        assertThat(dashboard.product()).isEqualTo(OK);
        assertThat(dashboard.settlement()).isNull();
        assertThat(dashboard.unavailable()).containsExactly("order", "orderDaily", "settlement");
    }

    @Test
    @DisplayName("다른 서비스 실패가 아닌 예외(store 버그)는 비우지 않고 그대로 올린다 — 500")
    void dashboard_whenUnexpectedError_propagates() {
        // given
        IllegalStateException bug = new IllegalStateException("버그");
        given(adapter.orderStats(STORE_ID)).willThrow(bug);
        given(adapter.orderDailyStats(STORE_ID, SellerDashboardService.DAILY_DAYS)).willReturn(OK);
        given(adapter.productStats(STORE_ID)).willReturn(OK);
        given(adapter.settlementStats(STORE_ID)).willReturn(OK);

        // when · then
        assertThatThrownBy(() -> service.dashboard(STORE_ID)).isInstanceOf(CompletionException.class).hasCause(bug);
    }
}
