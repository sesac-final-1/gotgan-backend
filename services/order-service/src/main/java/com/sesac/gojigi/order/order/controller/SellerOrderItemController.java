package com.sesac.gojigi.order.order.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.order.order.dto.request.SellerOrderItemIdsRequest;
import com.sesac.gojigi.order.order.dto.request.SellerShipRequest;
import com.sesac.gojigi.order.order.dto.response.SellerBulkResponse;
import com.sesac.gojigi.order.order.service.SellerDeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 판매자 주문 배송 처리 — 게이트웨이가 SELLER 역할을 확인하고 X-Store-Id 로 본인 스토어를 알려 준다 */
@Tag(name = "판매자 주문", description = "발주확인 · 송장 등록 · 배송완료 — 인가: 판매자 (X-Store-Id 본인 스토어만)")
@RestController
@RequestMapping("/api/seller/order-items")
@RequiredArgsConstructor
public class SellerOrderItemController {

    private static final String BULK_NOTE = """
            일괄 요청은 상품주문마다 따로 처리 — 일부가 실패해도 나머지는 반영되고, 실패는 failed 에 사유 code 로 온다
            (ORDER_ITEM_NOT_FOUND: 없거나 내 스토어가 아님 · ORDER_INVALID_STATUS: 지금 상태에서 할 수 없음 · 이미 처리됨).
            에러(요청 전체): 400 INVALID_INPUT · 403 FORBIDDEN(스토어 없음)""";

    private final SellerDeliveryService sellerDeliveryService;

    @Operation(summary = "발주확인 (결제완료 → 배송준비)", description = BULK_NOTE)
    @PatchMapping("/confirm")
    public ApiResponse<SellerBulkResponse> confirm(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                   @Valid @RequestBody SellerOrderItemIdsRequest request) {
        return ApiResponse.success("발주확인했습니다.",
                sellerDeliveryService.prepare(user.storeId(), user.userId(), request.orderItemIds()));
    }

    @Operation(summary = "송장 등록 (배송준비 → 배송중)", description = """
            택배사 · 송장번호 · 발송 일시를 남기고 배송중. 송장번호를 비우면 mock 번호(택배사 접두 + 상품주문 ID).
            커밋 후 OrderItemShipped 발행 → 배송 시작 메일. 발송 후 app.order.auto-deliver-after(운영 1시간 · 데모 1분)가 지나면 자동 배송완료.
            """ + BULK_NOTE)
    @PatchMapping("/ship")
    public ApiResponse<SellerBulkResponse> ship(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                @Valid @RequestBody SellerShipRequest request) {
        return ApiResponse.success("송장을 등록했습니다.",
                sellerDeliveryService.ship(user.storeId(), user.userId(), request.items()));
    }

    @Operation(summary = "배송완료 (배송중 → 배송완료)", description = """
            delivered_at 기록 — 자동 구매확정(배송완료 + 7일) · 반품 · 교환 기간(7일)의 기준. 커밋 후 OrderItemDelivered 발행 → 배송 완료 메일.
            """ + BULK_NOTE)
    @PatchMapping("/deliver")
    public ApiResponse<SellerBulkResponse> deliver(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                   @Valid @RequestBody SellerOrderItemIdsRequest request) {
        return ApiResponse.success("배송완료 처리했습니다.",
                sellerDeliveryService.deliver(user.storeId(), user.userId(), request.orderItemIds()));
    }
}
