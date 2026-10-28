package com.sesac.gojigi.product.product.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 공개 상품 목록 카드 (product.md 9절) — 별점 · 리뷰 수 · 스토어 이름은 화면이 다른 API 로 붙인다 */
public record ProductSummaryResponse(
        Long productId, String name, Long storeId, Long categoryId, Long topCategoryId,
        long price, BigDecimal discountRate, long salePrice,
        String imageUrl, boolean soldOut, OffsetDateTime createdAt) {
}
