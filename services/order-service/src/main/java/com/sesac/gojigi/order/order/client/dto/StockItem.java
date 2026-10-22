package com.sesac.gojigi.order.order.client.dto;

/** 재고 예약 · 해제의 옵션 1건 (docs/api/product.md 1 · 2절) */
public record StockItem(Long productOptionId, int quantity) {
}
