package com.sesac.gojigi.order.order.client.dto;

import java.time.OffsetDateTime;

/**
 * payment 응답 객체 PaymentResponse (payment.md 공통). portonePaymentId 는 브라우저 결제창 paymentId.
 * status: READY · PAID · PARTIAL_CANCELLED · CANCELLED · FAILED — 다른 서비스 값이라 문자열로 받는다.
 */
public record PaymentResponse(String orderNo, String portonePaymentId, String status, long amount,
                              long cancelledAmount, String method, OffsetDateTime paidAt) {
}
