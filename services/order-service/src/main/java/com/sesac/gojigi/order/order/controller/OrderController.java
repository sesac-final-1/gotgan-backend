package com.sesac.gojigi.order.order.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.order.order.dto.request.OrderCreateRequest;
import com.sesac.gojigi.order.order.dto.response.OrderCreateResponse;
import com.sesac.gojigi.order.saga.service.PlaceOrderSaga;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 구매자 주문 — 게이트웨이가 JWT 를 검증하고 X-User-Id 로 본인을 알려 준다 */
@Tag(name = "주문", description = "주문 생성 · 결제 — 인가: 로그인 회원 (X-User-Id)")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final PlaceOrderSaga placeOrderSaga;

    @Operation(summary = "주문 생성 (주문서 제출)", description = """
            주문 Saga 1 ~ 4단계: 주문 PENDING · 상품주문 스냅샷 → 포인트 사용(user) → 재고 예약(product) → 결제 준비(payment).
            가격 · 배송비 · 결제 금액은 서버가 다시 계산한다. 응답의 portonePaymentId · payAmt · orderName 으로 포트원 결제창을 띄운다.
            전액 포인트(payAmt 0)는 결제창 없이 바로 PAID (portonePaymentId null).
            실패하면 앞 단계를 되돌리고 주문 FAILED — 결제창을 띄우지 않는다.
            에러: 400 INVALID_INPUT · ORDER_POINT_EXCEEDS_AMOUNT / 404 ORDER_BUYER_NOT_FOUND /
            409 ORDER_PRODUCT_NOT_PURCHASABLE · ORDER_OUT_OF_STOCK · ORDER_POINT_INSUFFICIENT / 502 EXTERNAL_API_ERROR""")
    @PostMapping
    public ResponseEntity<ApiResponse<OrderCreateResponse>> create(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                                  @Valid @RequestBody OrderCreateRequest request) {
        OrderCreateResponse created = placeOrderSaga.place(user.userId(), request);
        String message = created.payAmt() == 0 ? "주문을 완료했습니다." : "주문을 생성했습니다. 결제를 진행해 주세요.";
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(message, created));
    }
}
