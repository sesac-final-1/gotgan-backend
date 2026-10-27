package com.sesac.gojigi.user.user.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * OrderItemConfirmed payload (docs/events.md) — user 는 buyerId · itemAmt(누적 구매확정 금액 → 등급) · earnedPoint(포인트 적립)만 쓴다.
 * earnedPoint 는 order 가 정한 상품주문 적립 포인트(주문 시점 스냅샷) — user 가 다시 계산하지 않는다.
 * 나머지(storeId · productId · 카테고리 등)는 settlement · review 용이라 읽지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderItemConfirmedPayload(Long orderItemId, String orderNo, Long buyerId, long itemAmt, long earnedPoint) {
}
