package com.sesac.gojigi.order.order.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.order.order.dto.request.SellerOrderItemIdsRequest;
import com.sesac.gojigi.order.order.dto.request.SellerShipRequest;
import com.sesac.gojigi.order.order.dto.response.SellerBulkResponse;
import com.sesac.gojigi.order.order.dto.response.SellerOrderItemListResponse;
import com.sesac.gojigi.order.order.dto.response.SellerOrderItemResponse;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import com.sesac.gojigi.order.order.service.SellerDeliveryService;
import com.sesac.gojigi.order.order.service.SellerOrderQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 판매자 주문 조회 · 배송 처리 — 게이트웨이가 SELLER 역할을 확인하고 X-Store-Id 로 본인 스토어를 알려 준다 */
@Tag(name = "판매자 주문", description = "목록 · 상세 · 발주확인 · 송장 등록 · 배송완료 — 인가: 판매자 (X-Store-Id 본인 스토어만)")
@RestController
@RequestMapping("/api/seller/order-items")
@RequiredArgsConstructor
public class SellerOrderItemController {

    private static final String BULK_NOTE = """
            일괄 요청은 상품주문마다 따로 처리 — 일부가 실패해도 나머지는 반영되고, 실패는 failed 에 사유 code 로 온다
            (ORDER_ITEM_NOT_FOUND: 없거나 내 스토어가 아님 · ORDER_INVALID_STATUS: 지금 상태에서 할 수 없음 · 이미 처리됨).
            에러(요청 전체): 400 INVALID_INPUT · 403 FORBIDDEN(스토어 없음)""";

    private final SellerDeliveryService sellerDeliveryService;
    private final SellerOrderQueryService sellerOrderQueryService;

    @Operation(summary = "판매자 주문 목록", description = """
            내 스토어 상품주문 — 최신 주문 먼저, 서버 페이징(page 0부터 · size 기본 20 · 최대 100).
            status: 상태 탭(없으면 전체), q: 상품주문번호 · 구매자 · 수령인 · 상품명 · 송장번호 부분 일치.
            statusCounts 는 탭 숫자(검색 · 탭과 무관하게 전체). 결제 전(ORDERED · 결제 대기 · 실패 주문)은 보이지 않는다.
            에러: 400 INVALID_INPUT · 403 FORBIDDEN(스토어 없음)""")
    @GetMapping
    public ApiResponse<SellerOrderItemListResponse> list(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                         @RequestParam(required = false) OrderItemStatus status,
                                                         @RequestParam(required = false) @Size(max = 50) String q,
                                                         @RequestParam(defaultValue = "0") @Min(0) int page,
                                                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success("주문 목록을 조회했습니다.",
                sellerOrderQueryService.list(user.storeId(), status, q, page, size));
    }

    @Operation(summary = "판매자 주문 상세", description = """
            상품주문 1건 — 상품 · 금액 스냅샷, 구매자(공개용 이름) · 배송지, 택배사 · 송장 · 발송/배송완료 시각.
            에러: 404 ORDER_ITEM_NOT_FOUND(없음 · 남의 스토어 · 결제 전) · 403 FORBIDDEN(스토어 없음)""")
    @GetMapping("/{orderItemId}")
    public ApiResponse<SellerOrderItemResponse> get(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                    @PathVariable Long orderItemId) {
        return ApiResponse.success("주문을 조회했습니다.", sellerOrderQueryService.get(user.storeId(), orderItemId));
    }

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
