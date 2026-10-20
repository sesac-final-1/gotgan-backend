package com.sesac.gojigi.payment.payment.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 포트원 V2 결제 취소 요청 (CancelPaymentBody 중 쓰는 필드).
 * amount 는 전액 취소여도 항상 명시한다 — 우리가 선기록한 금액과 포트원이 처리한 금액이 같아야 대조할 수 있다.
 * currentCancellableAmount: 우리가 아는 취소 직전 잔액 — 포트원 쪽과 다르면 포트원이 거절(CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN)해
 * 응답이 끊긴 취소를 다시 보내도 두 번 취소되지 않는다. 포트원 API 에는 멱등 키가 없어 이것이 이중 환불 방지 수단이다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PortOneCancelRequest(Long amount, String reason, Long currentCancellableAmount) {

    public static PortOneCancelRequest of(long amount, String reason, long currentCancellableAmount) {
        return new PortOneCancelRequest(amount, reason, currentCancellableAmount);
    }
}
