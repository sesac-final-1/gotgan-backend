package com.sesac.gojigi.order.order.client.dto;

/**
 * product 가격 · 구매 가능 조회 — GET /internal/product-options?ids= (product.md 4절).
 * 장바구니 표시 · 주문서 · 주문 Saga 1단계(상품주문 스냅샷)에서 쓴다. 응답에서 빠진 옵션은 구매 불가로 처리.
 * unitPrice = salePrice + extraPrice. optionName 은 옵션 없는 상품이면 null, stock 은 표시용.
 */
public record ProductOptionResponse(Long productOptionId, Long productId, String productName, String optionName,
                                    Long storeId, Long categoryId, Long topCategoryId, long salePrice, long extraPrice,
                                    long unitPrice, int stock, boolean purchasable, String imageUrl) {
}
