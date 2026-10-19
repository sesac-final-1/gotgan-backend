package com.sesac.gojigi.settlement.settlement.dto.event;

import java.time.OffsetDateTime;

/**
 * OrderItemConfirmed payload (docs/events.md). itemAmt = 상품주문 금액(포인트 차감 전) = 판매자 매출.
 * categoryId 는 하위, topCategoryId 는 상위 — 판매수수료율은 상위 카테고리 기준 (10/16 #22 추가)
 */
public record OrderItemConfirmedPayload(Long orderItemId, String orderNo, Long buyerId, Long storeId, Long productId,
        String productName, Long categoryId, Long topCategoryId, long itemAmt, OffsetDateTime confirmedAt) {
}
