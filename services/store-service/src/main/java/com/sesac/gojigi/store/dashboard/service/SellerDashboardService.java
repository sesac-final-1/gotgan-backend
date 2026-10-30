package com.sesac.gojigi.store.dashboard.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.store.dashboard.client.DashboardClientAdapter;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 판매자 대시보드 (STR-07, store.md 15절) — order · product · settlement 통계를 모아 한 번에. 한 곳이 실패해도 200 + 그 영역 null +
 * unavailable[] (부분 응답, admin 대시보드와 같은 방식). 스토어 상태(정지 · 폐점)는 보지 않는다 — 남은 주문 · 정산을 처리해야 하고,
 * 상태 배너는 화면이 내 스토어 조회(13-1절)의 status 로 띄운다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerDashboardService {

    /** 일별 결제금액 차트 기간 (최근 30일) */
    static final int DAILY_DAYS = 30;

    private final DashboardClientAdapter dashboardClientAdapter;

    /** 영역 호출 스레드 — 가상 스레드, 요청 스레드의 Observation(traceId · MDC)을 이어받는다 */
    private static final ExecutorService SECTIONS = ContextExecutorService.wrap(
            Executors.newVirtualThreadPerTaskExecutor(), () -> ContextSnapshotFactory.builder().build().captureAll());

    /** 네 영역을 동시에 호출 — 응답 시간은 가장 느린 한 곳(합이 아니라 최댓값) */
    public Dashboard dashboard(Long storeId) {
        CompletableFuture<Section> order = section("order", () -> dashboardClientAdapter.orderStats(storeId));
        CompletableFuture<Section> orderDaily = section("orderDaily", () -> dashboardClientAdapter.orderDailyStats(storeId, DAILY_DAYS));
        CompletableFuture<Section> product = section("product", () -> dashboardClientAdapter.productStats(storeId));
        CompletableFuture<Section> settlement = section("settlement", () -> dashboardClientAdapter.settlementStats(storeId));
        List<Section> sections = Stream.of(order, orderDaily, product, settlement).map(CompletableFuture::join).toList();
        List<String> unavailable = sections.stream().filter(Section::failed).map(Section::name).toList();
        return new Dashboard(sections.get(0).value(), sections.get(1).value(), sections.get(2).value(), sections.get(3).value(), unavailable);
    }

    private static CompletableFuture<Section> section(String name, Supplier<JsonNode> call) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new Section(name, call.get(), false);
            } catch (BusinessException e) {
                log.warn("대시보드 {} 영역 비움 - code={}", name, e.getErrorCode().getCode());
                return new Section(name, null, true);
            }
        }, SECTIONS);
    }

    private record Section(String name, JsonNode value, boolean failed) {
    }

    /**
     * order = /internal/stats/orders?storeId=, orderDaily = /internal/stats/orders/daily(최근 30일),
     * product · settlement = 각 /internal/stats/**?storeId= 의 store — 모두 그대로
     */
    public record Dashboard(JsonNode order, JsonNode orderDaily, JsonNode product, JsonNode settlement, List<String> unavailable) {
    }
}
