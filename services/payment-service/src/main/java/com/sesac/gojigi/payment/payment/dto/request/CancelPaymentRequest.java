package com.sesac.gojigi.payment.payment.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 결제 취소 — amount 를 생략하면 전액 취소(주문 Saga 보상), 넣으면 부분 취소(취소 · 반품 Saga).
 * sagaId 가 멱등 키: 같은 sagaId 재요청은 포트원을 다시 부르지 않고 기존 결과를 돌려준다.
 */
public record CancelPaymentRequest(
        @NotBlank @Size(max = 36) String sagaId,
        Long orderItemId,
        @Min(1) Long amount,
        @NotBlank @Size(max = 200) String reason) {

    public boolean isFull() {
        return amount == null;
    }
}
