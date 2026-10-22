package com.sesac.gojigi.order.order.client.dto;

/** user 포인트 사용 결과 — balanceAfter 는 사용 직후 잔액 */
public record PointUseResponse(String sagaId, Long userId, String orderNo, long amount, long balanceAfter) {
}
