package com.sesac.gojigi.order.order.client.dto;

/**
 * user 포인트 사용 취소 — DELETE /internal/points/use/{sagaId} (주문 Saga 보상).
 * 되돌릴 사용이 없었으면 restoredAmount 0 · balanceAfter null (정상 응답).
 */
public record PointRestoreResponse(String sagaId, long restoredAmount, Long balanceAfter) {
}
