package com.sesac.gojigi.store.apply.entity;

/** 입점 신청 상태 — 심사대기 → (보완요청) → 승인 / 반려 (storedb store_application.status) */
public enum ApplicationStatus {
    PENDING,
    SUPPLEMENT,
    APPROVED,
    REJECTED
}
