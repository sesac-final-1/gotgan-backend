package com.sesac.gojigi.admin.dashboard.service;

import com.sesac.gojigi.admin.dashboard.client.OrderClientAdapter;
import com.sesac.gojigi.admin.product.client.AdminProductClientAdapter;
import com.sesac.gojigi.admin.settlement.client.SettlementClientAdapter;
import com.sesac.gojigi.common.web.exception.BusinessException;
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
 * 관리자 대시보드 (ADM-02) — 서비스별 통계를 모아 한 번에. 한 곳이 실패해도 200 + 그 영역 null + unavailable[] (부분 응답, 컨벤션 공통 규칙).
 * 지금은 내부 통계 API 가 있는 settlement · order(전체 · 일별) · product 만 — store(입점 심사 대기) · user 통계는 그 API 가 생기면 추가
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    /** 일별 거래액 차트 기간 (ADM-04 최근 30일) */
    static final int DAILY_DAYS = 30;

    private final SettlementClientAdapter settlementClientAdapter;
    private final OrderClientAdapter orderClientAdapter;
    private final AdminProductClientAdapter adminProductClientAdapter;

    /** 영역 호출 스레드 — 가상 스레드, 요청 스레드의 Observation(traceId · MDC)을 이어받는다 */
    private static final ExecutorService SECTIONS = ContextExecutorService.wrap(
            Executors.newVirtualThreadPerTaskExecutor(), () -> ContextSnapshotFactory.builder().build().captureAll());

    /** 네 영역을 동시에 호출 — 응답 시간은 가장 느린 한 곳(합이 아니라 최댓값) */
    public Dashboard dashboard() {
        CompletableFuture<Section> settlement = section("settlement", settlementClientAdapter::platformStats);
        CompletableFuture<Section> order = section("order", orderClientAdapter::platformStats);
        CompletableFuture<Section> orderDaily = section("orderDaily", () -> orderClientAdapter.dailyStats(DAILY_DAYS));
        CompletableFuture<Section> product = section("product", adminProductClientAdapter::platformStats);
        List<Section> sections = Stream.of(settlement, order, orderDaily, product).map(CompletableFuture::join).toList();
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
     * settlement = /internal/stats/settlements 의 platform, order = /internal/stats/orders(전체), orderDaily = /internal/stats/orders/daily(최근 30일),
     * product = /internal/stats/products 의 platform — 모두 그대로
     */
    public record Dashboard(JsonNode settlement, JsonNode order, JsonNode orderDaily, JsonNode product, List<String> unavailable) {
    }
}
