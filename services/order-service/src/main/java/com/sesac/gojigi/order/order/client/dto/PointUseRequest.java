package com.sesac.gojigi.order.order.client.dto;

/** user 포인트 사용 — POST /internal/points/use (주문 Saga POINT_USED). sagaId 멱등, amount 1 이상 */
public record PointUseRequest(String sagaId, Long userId, String orderNo, long amount) {
}
