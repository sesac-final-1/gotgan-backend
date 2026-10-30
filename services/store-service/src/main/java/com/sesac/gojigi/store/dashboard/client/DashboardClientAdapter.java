package com.sesac.gojigi.store.dashboard.client;

import com.sesac.gojigi.store.store.client.RemoteCalls;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/**
 * 대시보드 통계 호출 — 서킷 브레이커 이름 = 대상 서비스(order · product · settlement), 조회(GET)라 재시도 1회.
 * 실패 변환 규칙은 RemoteCalls(4xx · 장애 모두 502 BusinessException) — SellerDashboardService 가 그 영역만 비운다.
 * product · settlement 는 응답의 store 객체만 꺼낸다(storeId 로 부르면 store 만 채워짐)
 */
@Component
public class DashboardClientAdapter {

    private final StatsClient orderStatsClient;
    private final StatsClient productStatsClient;
    private final StatsClient settlementStatsClient;

    public DashboardClientAdapter(@Qualifier("orderStatsClient") StatsClient orderStatsClient,
            @Qualifier("productStatsClient") StatsClient productStatsClient,
            @Qualifier("settlementStatsClient") StatsClient settlementStatsClient) {
        this.orderStatsClient = orderStatsClient;
        this.productStatsClient = productStatsClient;
        this.settlementStatsClient = settlementStatsClient;
    }

    @Retry(name = "order")
    @CircuitBreaker(name = "order", fallbackMethod = "orderStatsFallback")
    public JsonNode orderStats(Long storeId) {
        try {
            return orderStatsClient.orderStats(storeId).data();
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.rejected("order", "주문 통계 조회", e);
        }
    }

    @Retry(name = "order")
    @CircuitBreaker(name = "order", fallbackMethod = "orderDailyStatsFallback")
    public JsonNode orderDailyStats(Long storeId, int days) {
        try {
            return orderStatsClient.orderDailyStats(storeId, days).data();
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.rejected("order", "일별 주문 통계 조회", e);
        }
    }

    @Retry(name = "product")
    @CircuitBreaker(name = "product", fallbackMethod = "productStatsFallback")
    public JsonNode productStats(Long storeId) {
        try {
            return productStatsClient.productStats(storeId).data().get("store");
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.rejected("product", "상품 통계 조회", e);
        }
    }

    @Retry(name = "settlement")
    @CircuitBreaker(name = "settlement", fallbackMethod = "settlementStatsFallback")
    public JsonNode settlementStats(Long storeId) {
        try {
            return settlementStatsClient.settlementStats(storeId).data().get("store");
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.rejected("settlement", "정산 통계 조회", e);
        }
    }

    JsonNode orderStatsFallback(Long storeId, Throwable t) {
        throw RemoteCalls.unavailable("order", "주문 통계 조회", t);
    }

    JsonNode orderDailyStatsFallback(Long storeId, int days, Throwable t) {
        throw RemoteCalls.unavailable("order", "일별 주문 통계 조회", t);
    }

    JsonNode productStatsFallback(Long storeId, Throwable t) {
        throw RemoteCalls.unavailable("product", "상품 통계 조회", t);
    }

    JsonNode settlementStatsFallback(Long storeId, Throwable t) {
        throw RemoteCalls.unavailable("settlement", "정산 통계 조회", t);
    }
}
