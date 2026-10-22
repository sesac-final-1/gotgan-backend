package com.sesac.gojigi.order.order.entity;

/** 주문(결제 단위) 상태 — 배송 · 클레임 상태는 상품주문(OrderItemStatus)에서 관리 */
public enum OrderStatus {
    /** 주문 생성 ~ 결제 검증 전 (결제 대기) */
    PENDING,
    /** 결제 완료 */
    PAID,
    /** 결제 실패 · 결제 대기 만료 · Saga 보상 완료 */
    FAILED,
    /** 주문 전체 취소 */
    CANCELLED
}
