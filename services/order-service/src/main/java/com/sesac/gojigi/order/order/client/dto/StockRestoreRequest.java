package com.sesac.gojigi.order.order.client.dto;

/**
 * product 재고 복원 — POST /internal/stock/restorations (product.md 3절, 취소 · 반품 Saga STOCK_RESTORED).
 * claimId 멱등. 클레임 1건 = 상품주문 1건 = 옵션 1개, 수량은 클레임 수량 그대로.
 */
public record StockRestoreRequest(Long claimId, Long productOptionId, int quantity) {
}
