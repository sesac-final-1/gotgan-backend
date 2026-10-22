package com.sesac.gojigi.order.order.client.dto;

import java.util.List;

/**
 * product 재고 예약 — POST /internal/stock/reservations (product.md 1절, 주문 Saga STOCK_RESERVED).
 * 전부 예약하거나 하나도 안 함. sagaId 멱등, 같은 옵션 ID 중복 금지.
 */
public record StockReserveRequest(String sagaId, List<StockItem> items) {
}
