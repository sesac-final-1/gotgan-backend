package com.sesac.gojigi.order.saga.entity;

/** Saga 현재 단계 — 마지막으로 끝낸 단계 (V3 current_step 주석) */
public enum SagaStep {
    // PLACE_ORDER
    ORDER_CREATED,
    POINT_USED,
    STOCK_RESERVED,
    PAYMENT_READY,
    PAYMENT_VERIFIED,
    // CANCEL_ITEM · RETURN_ITEM — 결제 취소 409 PAYMENT_CANCEL_PENDING 이면 PAYMENT_CANCEL_REQUESTED 에서 재시도
    PAYMENT_CANCEL_REQUESTED,
    PAYMENT_CANCELLED,
    STOCK_RESTORED,
    POINT_RESTORED,
    // 공통
    DONE
}
