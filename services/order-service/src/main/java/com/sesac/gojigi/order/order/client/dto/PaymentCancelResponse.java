package com.sesac.gojigi.order.order.client.dto;

import java.time.OffsetDateTime;

/**
 * payment 결제 취소 응답 (payment.md 3절). 취소할 것이 없었으면 cancel 이 null,
 * 결제 자체가 없으면(준비 전에 실패) 응답 data 가 null 이다.
 */
public record PaymentCancelResponse(PaymentResponse payment, Cancel cancel) {

    public record Cancel(String sagaId, Long orderItemId, long cancelAmount, String portoneCancellationId,
                         OffsetDateTime cancelledAt) {
    }
}
