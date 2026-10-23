package com.sesac.gojigi.order.order.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.order.order.dto.request.OrderCreateRequest;
import com.sesac.gojigi.order.order.dto.response.OrderCreateResponse;
import com.sesac.gojigi.order.order.dto.response.OrderDetailResponse;
import com.sesac.gojigi.order.order.dto.response.OrderListResponse;
import com.sesac.gojigi.order.order.dto.response.OrderPaymentConfirmResponse;
import com.sesac.gojigi.order.order.service.OrderQueryService;
import com.sesac.gojigi.order.saga.service.PlaceOrderSaga;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 구매자 주문 — 게이트웨이가 JWT 를 검증하고 X-User-Id 로 본인을 알려 준다 */
@Tag(name = "주문", description = "주문 생성 · 결제 · 내 주문 조회 — 인가: 로그인 회원 (X-User-Id)")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final PlaceOrderSaga placeOrderSaga;
    private final OrderQueryService orderQueryService;

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

    @Operation(summary = "결제 확인 (결제창 종료 후)", description = """
            주문 Saga 6 · 7단계: payment 가 포트원에 직접 조회해 결제 상태 · 금액(주문의 실결제금액)을 확인하면 주문 PAID · 상품주문 PAYMENT_DONE.
            브라우저가 보내는 값은 주문번호뿐. 이미 PAID 면 같은 응답(새로고침 · 재전송 안전). 모바일 리다이렉트는 완료 페이지에서 호출.
            409 ORDER_PAYMENT_NOT_COMPLETED(아직 미결제) · 502 EXTERNAL_API_ERROR(결과 모름)는 주문을 결제 대기로 둔다 — 15분 뒤 만료 스케줄러가 확정 · 보상.
            에러: 404 ORDER_NOT_FOUND(없는 주문 · 다른 회원) / 409 ORDER_PAYMENT_NOT_COMPLETED · ORDER_PAYMENT_FAILED(금액 불일치 · 끝난 결제 —
            재고 · 포인트 되돌리고 주문 FAILED) · ORDER_INVALID_STATUS(이미 실패 · 처리 중인 주문) / 502 EXTERNAL_API_ERROR""")
    @PostMapping("/{orderNo}/payment/confirm")
    public ApiResponse<OrderPaymentConfirmResponse> confirmPayment(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                                   @PathVariable String orderNo) {
        return ApiResponse.success("결제를 완료했습니다.", placeOrderSaga.confirm(user.userId(), orderNo));
    }

    @Operation(summary = "내 주문 목록", description = """
            인가: 로그인 회원 본인. 최신 주문 먼저 · 페이징 없음(수백 건 이하라 전체 1회 — 기간 탭은 프론트가 거른다).
            결제 대기(PENDING) · 실패(FAILED) 주문도 포함 — 그 상품주문은 ORDERED(주문완료). statusCounts 는 상품주문 상태별 개수.
            에러: 401 UNAUTHORIZED""")
    @GetMapping
    public ApiResponse<OrderListResponse> list(@Parameter(hidden = true) @CurrentUser AuthUser user) {
        return ApiResponse.success("주문 목록을 조회했습니다.", orderQueryService.getMyOrders(user.userId()));
    }

    @Operation(summary = "내 주문 상세", description = """
            인가: 로그인 회원 본인. 상품주문별 상태 · 배송지 · 금액 · 사용/적립 포인트 · 결제수단(payment).
            payment 장애 · 0원 주문이면 payment 는 null 이고 나머지는 그대로 응답한다.
            에러: 401 UNAUTHORIZED · 404 ORDER_NOT_FOUND(없는 주문번호 · 다른 회원의 주문)""")
    @GetMapping("/{orderNo}")
    public ApiResponse<OrderDetailResponse> detail(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                   @PathVariable String orderNo) {
        return ApiResponse.success("주문 상세를 조회했습니다.", orderQueryService.getMyOrder(user.userId(), orderNo));
    }
}
