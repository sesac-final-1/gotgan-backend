package com.sesac.gojigi.product.product.dto.response;

import com.sesac.gojigi.product.product.entity.ProductStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 판매자 상품 목록 한 행 (product.md 11절) — 관리자 스토어 상품 목록(6-1절)도 같은 모양.
 * hiddenReason: 쇼핑몰에 안 보이는 이유 STOPPED · BLOCKED · CATEGORY_INACTIVE · STORE_INACTIVE, 보이면 null
 */
public record SellerProductResponse(
        Long productId,
        String name,
        Long categoryId,
        String categoryName,
        String topCategoryName,
        long price,
        BigDecimal discountRate,
        long salePrice,
        String imageUrl,
        long totalStock,
        boolean soldOut,
        ProductStatus status,
        String statusReason,
        boolean displayable,
        String hiddenReason,
        OffsetDateTime createdAt) {
}
