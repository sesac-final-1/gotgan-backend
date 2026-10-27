package com.sesac.gojigi.product.stock.entity;

/** 재고 원장 종류 — 수량은 항상 양수, 증감 방향은 종류로 판단 */
public enum StockType {
    /** 주문 Saga 예약 차감 (ref_key = sagaId) */
    RESERVE,
    /** 예약 해제 (ref_key = sagaId) — 예약이 없을 때는 수량 0 표식 */
    RELEASE,
    /** 취소 · 반품 복원 (ref_key = claim-{claimId}) */
    RESTORE
}
