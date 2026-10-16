package com.sesac.gojigi.payment.payment.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 결제 준비 (주문 Saga) — 0원 주문은 order 가 이 단계를 건너뛴다 (docs/api/payment.md 1절) */
public record PreparePaymentRequest(
        @NotNull Long orderId,
        @NotBlank @Size(max = 20) String orderNo,
        @Min(value = 1, message = "결제 금액은 1원 이상이어야 합니다.") long amount,
        @NotBlank @Size(max = 36) String sagaId) {
}
