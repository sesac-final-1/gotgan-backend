package com.sesac.gojigi.store.store.entity;

/**
 * 스토어 상태 — store.status 값. 정지는 관리자가 걸고 관리자가 푼다. 휴면은 없음(10/23 회의).
 * CLOSED(폐점)는 되돌릴 수 없는 마지막 상태 — 판매자가 PATCH /api/seller/store/close 로만(store.md 12절), 판매자 탈퇴 조건(user-service).
 */
public enum StoreStatus {
    ACTIVE,
    SUSPENDED,
    CLOSED
}
