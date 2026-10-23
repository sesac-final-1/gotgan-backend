package com.sesac.gojigi.user.point.entity;

/** 포인트 사용 sagaId 표식 상태 — point_use_saga.status */
public enum PointUseSagaStatus {
    /** 사용 처리됨 (사용 트랜잭션에서 함께 INSERT) */
    USED,
    /** 사용 기록 없이 취소가 먼저 옴 — 이후 늦게 도착한 사용은 409 POINT_USE_RELEASED */
    RELEASED
}
