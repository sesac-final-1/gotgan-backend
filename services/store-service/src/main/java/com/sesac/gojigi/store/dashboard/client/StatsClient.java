package com.sesac.gojigi.store.dashboard.client;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import tools.jackson.databind.JsonNode;

/**
 * 판매자 대시보드가 부르는 /internal/stats/** (STR-07) — 서비스마다 base URL 이 달라 같은 인터페이스를 세 번 만들지 않고
 * 경로를 메서드에 둔다(빈은 DashboardClientConfig 에서 서비스별로). 응답은 JsonNode 그대로 — 하위 필드가 늘어도 store 는 고치지 않는다.
 * 직접 쓰지 않고 DashboardClientAdapter(서킷 order · product · settlement)를 거친다
 */
public interface StatsClient {

    /** order — {storeId, toConfirmCount, toShipCount, claimCount, inProgressCount, todaySalesAmt}, 없는 스토어도 0 · 200 */
    @GetExchange("/internal/stats/orders")
    ApiResponse<JsonNode> orderStats(@RequestParam Long storeId);

    /** order — 오늘(KST) 포함 days 일 [{date, tradeAmt, orderCount}], 오래된 날부터 · 빈 날 0 */
    @GetExchange("/internal/stats/orders/daily")
    ApiResponse<JsonNode> orderDailyStats(@RequestParam Long storeId, @RequestParam int days);

    /** product — {store: {onSaleCount, lowStockCount, soldOutCount, stoppedCount, lowStockProducts[]}} (product.md 5절) */
    @GetExchange("/internal/stats/products")
    ApiResponse<JsonNode> productStats(@RequestParam Long storeId);

    /** settlement — {store: {pendingItemCount, pendingPayoutAmt, nextPayout, recentPaid[]}} */
    @GetExchange("/internal/stats/settlements")
    ApiResponse<JsonNode> settlementStats(@RequestParam Long storeId);
}
