package com.sesac.gojigi.payment.payment.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 포트원 V2 결제 취소 요청. amount 가 null 이면 전액 취소.
 * currentCancellableAmount: 우리가 아는 남은 금액 — 포트원 쪽과 다르면 포트원이 거절해 같은 취소가 두 번 나가는 것을 막는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PortOneCancelRequest(Long amount, String reason, Long currentCancellableAmount) {

    public static PortOneCancelRequest full(String reason, long currentCancellableAmount) {
        return new PortOneCancelRequest(null, reason, currentCancellableAmount);
    }

    public static PortOneCancelRequest partial(long amount, String reason, long currentCancellableAmount) {
        return new PortOneCancelRequest(amount, reason, currentCancellableAmount);
    }
}
