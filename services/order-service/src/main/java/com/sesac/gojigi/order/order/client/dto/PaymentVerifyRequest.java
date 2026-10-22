package com.sesac.gojigi.order.order.client.dto;

/** payment 결제 검증 — POST /internal/payments/verify (payment.md 2절, 주문 Saga PAYMENT_VERIFIED) */
public record PaymentVerifyRequest(String sagaId, String orderNo, long amount) {
}
