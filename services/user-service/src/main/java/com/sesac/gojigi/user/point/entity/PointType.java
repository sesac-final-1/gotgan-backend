package com.sesac.gojigi.user.point.entity;

/** 포인트 변동 유형 — amount 는 항상 양수, 증감 방향은 유형으로 판단 */
public enum PointType {
    /** 적립 (구매확정 — OrderItemConfirmed) — 잔액 증가 */
    EARN,
    /** 사용 (주문 결제) — 잔액 감소 */
    USE,
    /** 사용 환원 (Saga 보상 · 취소 · 반품) — 잔액 증가 */
    RESTORE,
    /** 적립 회수 (적립 후 취소 · 반품 — 적립이 구매확정 때라 지금 규칙에선 생기지 않는 안전장치) — 잔액 감소, 잔액이 모자라면 0 까지만 */
    RECLAIM;

    public boolean isIncrease() {
        return this == EARN || this == RESTORE;
    }
}
