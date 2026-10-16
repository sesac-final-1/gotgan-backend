package com.sesac.gojigi.payment.payment.dto.response;

import com.sesac.gojigi.payment.payment.entity.Payment;
import com.sesac.gojigi.payment.payment.entity.PaymentCancel;
import java.time.OffsetDateTime;

/** 취소 응답 — 결제 상태 + 이번 취소 1건 (취소할 것이 없었으면 cancel 은 null) */
public record PaymentCancelResponse(PaymentResponse payment, CancelDetail cancel) {

    public record CancelDetail(String sagaId, Long orderItemId, long cancelAmount, String portoneCancellationId,
            OffsetDateTime cancelledAt) {
    }

    public static PaymentCancelResponse of(Payment payment, PaymentCancel cancel) {
        CancelDetail detail = cancel == null ? null : new CancelDetail(cancel.getSagaId(), cancel.getOrderItemId(),
                cancel.getCancelAmount(), cancel.getPortoneCancellationId(),
                cancel.getCancelledAt().atZone(PaymentResponse.KST).toOffsetDateTime());
        return new PaymentCancelResponse(PaymentResponse.from(payment), detail);
    }
}
