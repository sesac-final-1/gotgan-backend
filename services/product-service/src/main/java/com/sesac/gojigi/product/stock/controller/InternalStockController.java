package com.sesac.gojigi.product.stock.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.product.stock.dto.request.StockReserveRequest;
import com.sesac.gojigi.product.stock.dto.response.StockReservationResponse;
import com.sesac.gojigi.product.stock.service.StockReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 재고 내부 API (product.md 1 · 2 · 3절) — order-service 주문 · 취소 Saga 가 클러스터 안에서 직접 호출 (게이트웨이 라우트 없음) */
@Tag(name = "재고 (내부)", description = "order-service 가 호출 — 인가: 내부")
@RestController
@RequestMapping("/internal/stock")
@RequiredArgsConstructor
public class InternalStockController {

    private final StockReservationService stockReservationService;

    @Operation(summary = "재고 예약", description = """
            주문 Saga 3단계. 전부 예약하거나 하나도 안 함 — 옵션 ID 오름차순으로 잠그고 조건부 차감 + stock_history RESERVE.
            같은 sagaId 재요청은 처음과 같은 응답(재고 다시 안 뺌). 가격은 확인하지 않음(order 가 4절 조회로 스냅샷).
            에러: 400 INVALID_INPUT(items 비었음 · quantity < 1 · 같은 옵션 중복) / 404 PRODUCT_OPTION_NOT_FOUND /
            409 PRODUCT_NOT_PURCHASABLE(판매중지 · 차단 · 카테고리/스토어 비활성 · 삭제) · STOCK_INSUFFICIENT · STOCK_RESERVATION_RELEASED(해제 표식 뒤 늦은 예약)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "예약 (재요청도 같은 응답)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "PRODUCT_OPTION_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "PRODUCT_NOT_PURCHASABLE · STOCK_INSUFFICIENT · STOCK_RESERVATION_RELEASED")
    })
    @PostMapping("/reservations")
    public ApiResponse<StockReservationResponse> reserve(@Valid @RequestBody StockReserveRequest request) {
        return ApiResponse.success("재고를 예약했습니다.", stockReservationService.reserve(request));
    }
}
