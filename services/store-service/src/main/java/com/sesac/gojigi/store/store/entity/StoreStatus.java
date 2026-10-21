package com.sesac.gojigi.store.store.entity;

/**
 * 스토어 상태 — store.status 값. 휴면은 판매자, 정지는 관리자가 걸고 건 쪽이 푼다.
 * CLOSED(폐점)는 되돌릴 수 없는 마지막 상태 — 판매자 탈퇴 조건(user-service). 폐점 API 는 다음 PR.
 */
public enum StoreStatus {
    ACTIVE,
    DORMANT,
    SUSPENDED,
    CLOSED
}
