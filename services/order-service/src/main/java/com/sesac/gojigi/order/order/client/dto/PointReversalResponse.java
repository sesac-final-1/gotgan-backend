package com.sesac.gojigi.order.order.client.dto;

/** user 포인트 환원 · 회수 결과 — reclaimedAmount 는 실제로 뺀 포인트, shortfallAmount 는 잔액이 모자라 못 뺀 차액 */
public record PointReversalResponse(Long claimId, Long orderItemId, long restoredAmount, long reclaimedAmount,
                                    long shortfallAmount, Long balanceAfter) {
}
