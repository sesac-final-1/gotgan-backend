package com.sesac.gojigi.order.claim.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * 클레임 처리 상태 (스펙 05장 클레임 상태표).
 * 취소: APPROVED(자동) → COMPLETED / 반품: REQUESTED → APPROVED(수거 요청) → COLLECTED(수거 완료 · 환불 Saga) → COMPLETED
 * 교환: REQUESTED → APPROVED → COLLECTED → RESHIPPED(재배송 송장) → COMPLETED(재배송 배송완료). 거부 · 구매자 철회는 REQUESTED 에서만
 */
public enum ClaimStatus {
    /** 구매자 요청 */
    REQUESTED,
    /** 판매자 승인 — 취소는 환불 Saga 진행, 반품 · 교환은 수거 요청 */
    APPROVED,
    /** 반품 · 교환 수거 완료 — 반품은 환불 Saga 진행 중 */
    COLLECTED,
    /** 교환 재배송 송장 등록 — 재배송이 배송완료되면 COMPLETED */
    RESHIPPED,
    /** 판매자 거부 (reject_reason 필수) */
    REJECTED,
    /** 구매자 철회 — 반품 · 교환을 판매자 승인 전에 거둬들임 */
    WITHDRAWN,
    /** 환불 · 재배송까지 끝남 */
    COMPLETED;

    /** 진행 중 — 한 상품주문에 진행 중 클레임 1개 (CLM-06) */
    public static final Set<ClaimStatus> OPEN = EnumSet.of(REQUESTED, APPROVED, COLLECTED, RESHIPPED);
    /** 끝남 — 판매자 클레임 처리완료 탭 */
    public static final Set<ClaimStatus> DONE = EnumSet.of(REJECTED, WITHDRAWN, COMPLETED);
}
