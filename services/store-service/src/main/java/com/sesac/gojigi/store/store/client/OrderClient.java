package com.sesac.gojigi.store.store.client;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.store.store.client.dto.OrderStatsResponse;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/** order-service 내부 API (ORD-10) — 직접 쓰지 않고 OrderClientAdapter(서킷 브레이커 "order")를 거친다 */
@HttpExchange("/internal/stats/orders")
public interface OrderClient {

    /** 스토어 주문 통계 — 없는 스토어도 0 · 200 */
    @GetExchange
    ApiResponse<OrderStatsResponse> storeStats(@RequestParam Long storeId);
}
