package com.sesac.gojigi.order.saga.entity;

/** Saga 진행 상태 */
public enum SagaStatus {
    /** 진행 중 */
    STARTED,
    /** 보상(되돌리기) 진행 중 */
    COMPENSATING,
    /** 정상 완료 */
    COMPLETED,
    /** 보상 완료 */
    COMPENSATED,
    /** 재시도 한도 초과 등 — 사람이 확인 */
    FAILED
}
