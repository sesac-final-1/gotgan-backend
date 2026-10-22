package com.sesac.gojigi.order.order.client.dto;

/**
 * payment 결제 준비 — POST /internal/payments (docs/api/payment.md 1절, 주문 Saga PAYMENT_READY).
 * 같은 orderNo · 같은 금액 재요청은 멱등. 0원(전액 포인트) 주문은 부르지 않고 바로 PAID.
 */
public record PaymentPrepareRequest(Long orderId, String orderNo, long amount, String sagaId) {
}
