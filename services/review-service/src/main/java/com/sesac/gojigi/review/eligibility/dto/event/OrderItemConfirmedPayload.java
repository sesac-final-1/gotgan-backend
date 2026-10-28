package com.sesac.gojigi.review.eligibility.dto.event;

import java.time.OffsetDateTime;

/**
 * OrderItemConfirmed payload 중 review 가 쓰는 필드 (gotgan-config docs/events.md) — 나머지(금액 · 카테고리 등)는 무시.
 * buyerDisplayName · optionName · thumbnailUrl 은 10/28 추가 — 그 전에 발행된 이벤트에는 없을 수 있다.
 */
public record OrderItemConfirmedPayload(Long orderItemId, String orderNo, Long buyerId, Long storeId, Long productId,
                                        String productName, OffsetDateTime confirmedAt, String buyerDisplayName,
                                        String optionName, String thumbnailUrl) {
}
