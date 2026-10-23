package com.sesac.gojigi.order.claim.entity;

/** 클레임 사유 — DDL 주석의 값. 클레임 스펙이 정해지면 추가할 수 있다 (VARCHAR 라 DDL 변경 없음) */
public enum ClaimReasonCode {
    /** 단순 변심 */
    CHANGE_OF_MIND,
    /** 상품 불량 */
    DEFECTIVE,
    /** 오배송 */
    WRONG_DELIVERY,
    /** 배송 지연 */
    DELAYED,
    /** 기타 — 상세 사유에 적는다 (스펙 05장 사유 목록) */
    ETC
}
