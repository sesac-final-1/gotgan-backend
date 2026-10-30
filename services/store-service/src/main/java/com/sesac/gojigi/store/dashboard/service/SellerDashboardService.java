package com.sesac.gojigi.store.dashboard.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.store.dashboard.client.DashboardClientAdapter;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 판매자 대시보드 (STR-07, store.md 15절) — order · product · settlement 통계를 모아 한 번에. 한 곳이 실패해도 200 + 그 영역 null +
 * unavailable[] (부분 응답, admin 대시보드와 같은 규칙). 스토어 상태(정지 · 폐점)는 보지 않는다 — 남은 주문 · 정산을 처리해야 하고,
 * 상태 배너는 화면이 내 스토어 조회(13-1절)의 status 로 띄운다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerDashboardService {

    /** 일별 결제금액 차트 기간 (최근 30일) */
    static final int DAILY_DAYS = 30;

    /** 영역 호출 스레드 — 가상 스레드, 요청 스레드의 Observation(traceId · MDC)을 이어받는다 */
    private static final ExecutorService STATS_CALLS = ContextExecutorService.wrap(
            Executors.newVirtualThreadPerTaskExecutor(), () -> ContextSnapshotFactory.builder().build().captureAll());

    private final DashboardClientAdapter dashboardClientAdapter;

    /** 네 영역을 동시에 호출 — 응답 시간은 가장 느린 한 곳(합이 아니라 최댓값). 표의 키 = 응답 필드 이름 = unavailable 값 */
    public Dashboard dashboard(Long storeId) {
        Map<String, CompletableFuture<JsonNode>> calls = new LinkedHashMap<>();
        calls.put("order", fetch("order", () -> dashboardClientAdapter.orderStats(storeId)));
        calls.put("orderDaily", fetch("orderDaily", () -> dashboardClientAdapter.orderDailyStats(storeId, DAILY_DAYS)));
        calls.put("product", fetch("product", () -> dashboardClientAdapter.productStats(storeId)));
        calls.put("settlement", fetch("settlement", () -> dashboardClientAdapter.settlementStats(storeId)));

        Map<String, JsonNode> results = new LinkedHashMap<>();
        calls.forEach((name, call) -> results.put(name, call.join()));
        List<String> unavailable = results.keySet().stream().filter(name -> results.get(name) == null).toList();
        return new Dashboard(results.get("order"), results.get("orderDaily"), results.get("product"), results.get("settlement"),
                unavailable);
    }

    /** 다른 서비스 실패(어댑터가 BusinessException 으로 바꿈)는 null = 그 영역 비움, 그 밖의 예외는 그대로 올려 500 */
    private static CompletableFuture<JsonNode> fetch(String name, Supplier<JsonNode> call) {
        return CompletableFuture.supplyAsync(call, STATS_CALLS).exceptionally(t -> {
            Throwable cause = t.getCause();   // supplyAsync 실패는 항상 CompletionException 으로 감싸져 온다
            if (cause instanceof BusinessException e) {
                log.warn("대시보드 {} 영역 비움 - code={}", name, e.getErrorCode().getCode());
                return null;
            }
            throw new CompletionException(cause);
        });
    }

    /**
     * order = /internal/stats/orders?storeId=, orderDaily = /internal/stats/orders/daily(최근 30일),
     * product · settlement = 각 /internal/stats/**?storeId= 의 store — 모두 그대로
     */
    public record Dashboard(JsonNode order, JsonNode orderDaily, JsonNode product, JsonNode settlement, List<String> unavailable) {
    }
}
