package com.sesac.gojigi.order.claim.entity;

/** 클레임 처리 상태 */
public enum ClaimStatus {
    /** 구매자 요청 */
    REQUESTED,
    /** 판매자 승인 — 환불 · 재고 복원 Saga 진행 */
    APPROVED,
    /** 판매자 거부 (reject_reason 필수) */
    REJECTED,
    /** 환불 · 재배송까지 끝남 */
    COMPLETED
}
