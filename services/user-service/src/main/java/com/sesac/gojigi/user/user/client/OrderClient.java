package com.sesac.gojigi.user.user.client;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.user.user.client.dto.BuyerOrderStatsResponse;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/** order-service 내부 API (InternalOrderStatsController) — 직접 쓰지 않고 OrderClientAdapter(서킷 브레이커 "order")를 거친다 */
@HttpExchange("/internal/stats/orders")
public interface OrderClient {

    /** 구매자 주문 통계 — 진행 중 상품주문 수 · 진행 중 클레임 수, 주문이 없어도 0 으로 200 */
    @GetExchange
    ApiResponse<BuyerOrderStatsResponse> getBuyerStats(@RequestParam Long buyerId);
}
