package com.sesac.gojigi.order.order.client;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.order.order.client.dto.ProductOptionResponse;
import com.sesac.gojigi.order.order.client.dto.StockReleaseRequest;
import com.sesac.gojigi.order.order.client.dto.StockReservationResponse;
import com.sesac.gojigi.order.order.client.dto.StockReserveRequest;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * product-service 내부 API (docs/api/product.md 1 · 2 · 4절) — 직접 쓰지 않고 ProductClientAdapter(서킷 브레이커 "product")를 거친다.
 * TODO(product 내부 API 구현 시): app.client.product-mock=false 로 바꾸고 MockProductClient 를 지운다.
 */
@HttpExchange("/internal")
public interface ProductClient {

    /** 가격 · 구매 가능 조회 — 없는 ID 는 목록에서 빠진다 */
    @GetExchange("/product-options")
    ApiResponse<List<ProductOptionResponse>> getOptions(@RequestParam List<Long> ids);

    /** 재고 예약 — 전부 또는 하나도 안 함, sagaId 멱등 */
    @PostExchange("/stock/reservations")
    ApiResponse<StockReservationResponse> reserve(@RequestBody StockReserveRequest request);

    /** 재고 해제 (Saga 보상) — 예약이 없으면 해제 표식만 남김 */
    @PostExchange("/stock/reservations/{sagaId}/release")
    ApiResponse<StockReservationResponse> release(@PathVariable String sagaId, @RequestBody StockReleaseRequest request);
}
