package com.sesac.gojigi.payment.payment.dto.response;

import com.sesac.gojigi.payment.payment.entity.Payment;
import com.sesac.gojigi.payment.payment.entity.PaymentStatus;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 결제 상태 응답 (docs/api/payment.md "응답 객체") */
public record PaymentResponse(
        String orderNo,
        String portonePaymentId,
        PaymentStatus status,
        long amount,
        long cancelledAmount,
        String method,
        OffsetDateTime paidAt) {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getOrderNo(), payment.getPortonePaymentId(), payment.getStatus(),
                payment.getAmount(), payment.getCancelledAmount(), payment.getMethod(),
                payment.getPaidAt() == null ? null : payment.getPaidAt().atZone(KST).toOffsetDateTime());
    }
}
