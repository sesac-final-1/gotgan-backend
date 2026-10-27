package com.sesac.gojigi.user.user.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * OrderItemConfirmed payload (docs/events.md) — user 는 buyerId · itemAmt(누적 구매확정 금액 → 등급)만 쓴다.
 * 나머지(storeId · productId · 카테고리 등)는 settlement · review 용이라 읽지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderItemConfirmedPayload(Long orderItemId, String orderNo, Long buyerId, long itemAmt) {
}
