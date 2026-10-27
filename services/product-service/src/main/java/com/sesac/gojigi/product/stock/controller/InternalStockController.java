package com.sesac.gojigi.product.stock.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.product.stock.dto.request.StockReleaseRequest;
import com.sesac.gojigi.product.stock.dto.request.StockReserveRequest;
import com.sesac.gojigi.product.stock.dto.request.StockRestoreRequest;
import com.sesac.gojigi.product.stock.dto.response.StockReservationResponse;
import com.sesac.gojigi.product.stock.service.StockReservationService;
import com.sesac.gojigi.product.stock.service.StockRestorationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final StockRestorationService stockRestorationService;

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

    @Operation(summary = "재고 해제", description = """
            주문 Saga 보상(결제 실패 · 금액 불일치 · 결제 대기 만료). items 는 예약 때 보낸 목록 그대로 — 되돌리는 수량은 product 의 RESERVE 기록 기준.
            RESERVE 있음 → 그 수량만큼 되돌림 + RELEASE / 이미 RELEASE → 같은 응답 / RESERVE 없음 → 옵션마다 수량 0 해제 표식(늦게 온 예약이 409 로 롤백), items 빈 목록.
            옵션 · 상품이 삭제 · 판매중지돼도 재고 수는 돌려놓음. 예약과 같은 옵션 행을 먼저 잠가 동시에 와도 한쪽만 반영.
            에러: 400 INVALID_INPUT(items 비었음 · quantity < 1 · 같은 옵션 중복 · sagaId 64자 초과)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "해제 · 표식 (재요청도 같은 응답)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT")
    })
    @PostMapping("/reservations/{sagaId}/release")
    public ApiResponse<StockReservationResponse> release(
            @Parameter(description = "order saga_instance.saga_id", example = "6c1f0a6e-2b9d-4c1e-9a1f-0b2c3d4e5f60") @PathVariable String sagaId,
            @Valid @RequestBody StockReleaseRequest request) {
        return ApiResponse.success("재고를 해제했습니다.", stockReservationService.release(sagaId, request));
    }

    @Operation(summary = "재고 복원", description = """
            취소 · 반품 Saga. 클레임 1건 = 상품주문 1건 = 옵션 1개, 수량은 클레임 수량 그대로.
            처음 → stock + qty + stock_history RESTORE(ref_key = claim-{claimId}) / 같은 claimId 재요청 → 아무것도 안 함(200).
            옵션 · 상품이 판매중지 · 삭제돼도 복원(재고 수만). 옵션 행을 먼저 잠가 같은 claimId 가 동시에 와도 한 번만 반영.
            에러: 400 INVALID_INPUT(claimId · 옵션 ID 없음 · quantity < 1) / 404 PRODUCT_OPTION_NOT_FOUND""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "복원 (재요청도 200)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "PRODUCT_OPTION_NOT_FOUND")
    })
    @PostMapping("/restorations")
    public ApiResponse<Void> restore(@Valid @RequestBody StockRestoreRequest request) {
        stockRestorationService.restore(request);
        return ApiResponse.success("재고를 복원했습니다.");
    }
}
