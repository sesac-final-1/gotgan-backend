package com.sesac.gojigi.settlement.settlement.entity;

/** 정산서 상태 — 지급예정 → (보류 ↔ 지급예정) → 지급완료. 지급완료는 끝 상태 */
public enum SettlementStatus {
    SCHEDULED,
    HELD,
    PAID
}
