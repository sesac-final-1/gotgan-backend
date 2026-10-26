package com.sesac.gojigi.admin.dashboard.client;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import tools.jackson.databind.JsonNode;

/** order-service 전체 주문 통계 (backend #83) — storeId 없이 부르면 {totalTradeAmt, orderCount}. OrderClientAdapter 를 거친다 */
@HttpExchange("/internal/stats/orders")
public interface OrderClient {

    @GetExchange
    ApiResponse<JsonNode> platformStats();
}
