package com.sesac.gojigi.order.order.entity;

/** 상품주문 상태 — 배송 · 구매확정 · 클레임의 상태 관리 단위 */
public enum OrderItemStatus {
    /** 결제 완료 (판매자 발주확인 전) */
    PAYMENT_DONE,
    /** 발주확인 — 배송 준비 중 */
    PREPARING,
    /** 송장 입력 — 배송 중 */
    SHIPPING,
    /** 배송 완료 — 자동 구매확정 대기 */
    DELIVERED,
    /** 구매확정 — 정산 기준 */
    CONFIRMED,
    CANCEL_REQUESTED,
    CANCELLED,
    RETURN_REQUESTED,
    RETURNED,
    EXCHANGE_REQUESTED
}
