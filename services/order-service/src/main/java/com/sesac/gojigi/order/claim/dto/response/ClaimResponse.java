package com.sesac.gojigi.order.claim.dto.response;

import com.sesac.gojigi.order.claim.entity.ClaimStatus;
import com.sesac.gojigi.order.claim.entity.ClaimType;

/**
 * 클레임 접수 결과. status: COMPLETED(환불 · 재고 · 포인트까지 끝남) · APPROVED(접수됨, 환불 처리 중 — 자동으로 마저 진행).
 * refundAmt = 카드 등으로 돌려받을 금액, restorePoint = 돌려받을 포인트.
 */
public record ClaimResponse(Long claimId, Long orderItemId, ClaimType type, ClaimStatus status, long refundAmt,
                            long restorePoint) {
}
