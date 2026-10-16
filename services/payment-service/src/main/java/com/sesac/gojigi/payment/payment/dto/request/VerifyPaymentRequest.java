package com.sesac.gojigi.payment.payment.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 결제 검증 (브라우저 confirm → order) — amount = order 가 계산한 기대 금액 */
public record VerifyPaymentRequest(
        @NotBlank @Size(max = 36) String sagaId,
        @NotBlank @Size(max = 20) String orderNo,
        @Min(1) long amount) {
}
