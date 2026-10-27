package com.sesac.gojigi.product.option.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 가격 · 구매 가능 조회 한 건 (product.md 4절) — order 의 ProductOptionResponse 와 같은 모양.
 * unitPrice = salePrice + extraPrice, stock 은 표시용(초과 판매 방지는 재고 예약의 조건부 UPDATE).
 */
public record ProductOptionResponse(
        Long productOptionId,
        Long productId,
        String productName,
        @Schema(description = "옵션 값 1 · 2 를 ' / ' 로 이은 표시용 — 옵션 없는 상품은 null", example = "오트밀 / M")
        String optionName,
        Long storeId,
        Long categoryId,
        @Schema(description = "상위 카테고리 — 구매확정 이벤트로 settlement 수수료 기준이 됨", example = "3")
        Long topCategoryId,
        long salePrice,
        long extraPrice,
        long unitPrice,
        int stock,
        @Schema(description = "판매중 · 카테고리 사용 중 · 스토어 운영 중 · 상품 · 옵션 삭제 안 됨. 품절은 따로 (stock 0)")
        boolean purchasable,
        String imageUrl) {
}
