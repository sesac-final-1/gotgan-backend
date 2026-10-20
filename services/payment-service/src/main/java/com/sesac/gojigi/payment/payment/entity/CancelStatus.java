package com.sesac.gojigi.payment.payment.entity;

/**
 * 취소 1건의 확정 상태 — 포트원을 부르기 전에 PENDING 으로 남긴다 (V2 선기록).
 * PENDING: 포트원 결과 미확정(요청 전 · 타임아웃 · PG 오류 · 포트원 REQUESTED) — 복구 스케줄러가 대조해 마무리
 * SUCCEEDED: 포트원 취소 완료 확인 — 이때만 결제 누적 취소액에 반영
 * FAILED: 포트원이 확정적으로 거절 — 같은 sagaId 로 다시 요청하면 PENDING 으로 되살려 재시도
 */
public enum CancelStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}
