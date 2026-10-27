package com.sesac.gojigi.admin.dashboard.client;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import tools.jackson.databind.JsonNode;

/** order-service 전체 주문 통계 (backend #83 · #107) — storeId 없이 부르면 전체. OrderClientAdapter 를 거친다 */
@HttpExchange("/internal/stats/orders")
public interface OrderClient {

    @GetExchange
    ApiResponse<JsonNode> platformStats();

    /** 결제일별 [{date, tradeAmt, orderCount}] — 오늘 포함 days 일 · 빈 날 0 (backend #107) */
    @GetExchange("/daily")
    ApiResponse<JsonNode> dailyStats(@RequestParam int days);
}
