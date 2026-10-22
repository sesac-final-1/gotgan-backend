package com.sesac.gojigi.order.order.client;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.order.order.client.dto.ProductOptionResponse;
import com.sesac.gojigi.order.order.client.dto.StockReleaseRequest;
import com.sesac.gojigi.order.order.client.dto.StockReservationResponse;
import com.sesac.gojigi.order.order.client.dto.StockReserveRequest;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * TODO(product 내부 API 구현 시 삭제): product-service 의 옵션 조회 · 재고 예약 API 가 없어 흉내 낸다 (app.client.product-mock=true, 기본값).
 * - 옵션은 ID 마다 같은 값(스토어 1 · 10,000원 · 구매 가능)으로 돌려준다 — 주문 스냅샷 이름은 "목업 상품 {ID}"
 * - 재고 예약 · 해제는 항상 성공
 */
@Component
@ConditionalOnProperty(name = "app.client.product-mock", havingValue = "true", matchIfMissing = true)
public class MockProductClient implements ProductClient {

    private static final long MOCK_PRICE = 10_000L;

    @Override
    public ApiResponse<List<ProductOptionResponse>> getOptions(List<Long> ids) {
        return ApiResponse.success("목업 옵션", ids.stream()
                .map(id -> new ProductOptionResponse(id, id, "목업 상품 " + id, null, 1L, 301L, 3L, MOCK_PRICE, 0L,
                        MOCK_PRICE, 100, true, null))
                .toList());
    }

    @Override
    public ApiResponse<StockReservationResponse> reserve(StockReserveRequest request) {
        return ApiResponse.success("목업 재고 예약", new StockReservationResponse(request.sagaId(), request.items()));
    }

    @Override
    public ApiResponse<StockReservationResponse> release(String sagaId, StockReleaseRequest request) {
        return ApiResponse.success("목업 재고 해제", new StockReservationResponse(sagaId, request.items()));
    }
}
