package com.sesac.gojigi.order.saga.entity;

/** Saga 종류 — 취소 · 반품 Saga 는 saga_instance.order_claim_id 가 있다 */
public enum SagaType {
    PLACE_ORDER,
    CANCEL_ITEM,
    RETURN_ITEM
}
