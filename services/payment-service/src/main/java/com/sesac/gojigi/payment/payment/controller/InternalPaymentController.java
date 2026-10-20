package com.sesac.gojigi.payment.payment.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.payment.payment.dto.request.CancelPaymentRequest;
import com.sesac.gojigi.payment.payment.dto.request.PreparePaymentRequest;
import com.sesac.gojigi.payment.payment.dto.request.VerifyPaymentRequest;
import com.sesac.gojigi.payment.payment.dto.response.PaymentCancelResponse;
import com.sesac.gojigi.payment.payment.dto.response.PaymentResponse;
import com.sesac.gojigi.payment.payment.service.PaymentService;
import com.sesac.gojigi.payment.payment.service.PaymentTxService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** order-service(Saga) 전용 — 게이트웨이 라우트 없음, 클러스터 내부에서만 호출 (docs/api/payment.md) */
@Tag(name = "결제 (내부)", description = "order-service 주문 · 취소 Saga 가 호출 — 인가: 내부")
@RestController
@RequestMapping("/internal/payments")
@RequiredArgsConstructor
public class InternalPaymentController {

    private final PaymentService paymentService;

    @Operation(summary = "결제 준비", description = "READY 생성 · portonePaymentId 발급. orderNo 멱등(같은 금액 재요청 200, 다른 금액 409 PAYMENT_ORDER_CONFLICT)")
    @PostMapping
    public ResponseEntity<ApiResponse<PaymentResponse>> prepare(@Valid @RequestBody PreparePaymentRequest request) {
        PaymentTxService.Prepared prepared = paymentService.prepare(request);
        return ResponseEntity.status(prepared.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.success("결제를 준비했습니다.", prepared.payment()));
    }

    @Operation(summary = "결제 검증", description = "포트원 조회 → PAID + 금액 일치면 PAID. 불일치 409 PAYMENT_AMOUNT_MISMATCH(자동 취소 후) · 미결제 409 PAYMENT_NOT_COMPLETED")
    @PostMapping("/verify")
    public ApiResponse<PaymentResponse> verify(@Valid @RequestBody VerifyPaymentRequest request) {
        return ApiResponse.success("결제를 확인했습니다.", paymentService.verify(request));
    }

    @Operation(summary = "결제 취소", description = "amount 생략 = 전액(주문 Saga 보상 — 되돌릴 게 없으면 아무 일도 안 함), amount 있음 = 부분. sagaId 멱등")
    @PostMapping("/{orderNo}/cancel")
    public ApiResponse<PaymentCancelResponse> cancel(@PathVariable String orderNo,
            @Valid @RequestBody CancelPaymentRequest request) {
        return ApiResponse.success("결제 취소를 처리했습니다.", paymentService.cancel(orderNo, request));
    }

    @Operation(summary = "결제 상태 조회", description = "결제 대기 만료 스케줄러용 — DB 상태만 (포트원 호출 없음)")
    @GetMapping
    public ApiResponse<PaymentResponse> get(@RequestParam String orderNo) {
        return ApiResponse.success("결제 상태를 조회했습니다.", paymentService.get(orderNo));
    }
}
