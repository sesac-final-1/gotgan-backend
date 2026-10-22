package com.sesac.gojigi.order.order.client.dto;

/**
 * payment 결제 취소 — POST /internal/payments/{orderNo}/cancel (payment.md 3 · 8절).
 * orderItemId · amount 를 비우면 전액 취소(주문 Saga 보상), 넣으면 부분 취소(취소 · 반품 Saga). 취소 금액은 order 가 계산.
 * 409 PAYMENT_CANCEL_PENDING · 타임아웃 · 5xx 는 "결과 모름" → 같은 sagaId 로 재시도 (포트원에 두 번 나가지 않음).
 */
public record PaymentCancelRequest(String sagaId, Long orderItemId, Long amount, String reason) {

    /** 전액 취소 — 주문 Saga 보상 (되돌릴 게 없으면 payment 가 아무 일도 안 함) */
    public static PaymentCancelRequest full(String sagaId, String reason) {
        return new PaymentCancelRequest(sagaId, null, null, reason);
    }

    /** 부분 취소 — 클레임 1건 = 취소 Saga 1건 = sagaId 1개 */
    public static PaymentCancelRequest partial(String sagaId, Long orderItemId, long amount, String reason) {
        return new PaymentCancelRequest(sagaId, orderItemId, amount, reason);
    }
}
