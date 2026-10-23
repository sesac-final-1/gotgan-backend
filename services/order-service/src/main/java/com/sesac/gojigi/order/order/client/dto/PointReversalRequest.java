package com.sesac.gojigi.order.order.client.dto;

/**
 * user 포인트 환원 · 회수 — POST /internal/points/reversals (취소 · 반품 Saga POINT_RESTORED). 멱등 키 claim-{claimId}-{orderItemId}.
 * restoreAmount = 돌려줄 사용 포인트(클레임에 기록한 값), reclaimAmount = 그 상품주문 적립 포인트 — user 가 실제 적립된 만큼까지만 회수.
 */
public record PointReversalRequest(Long claimId, Long orderItemId, Long userId, String orderNo, long restoreAmount,
                                   long reclaimAmount) {
}
