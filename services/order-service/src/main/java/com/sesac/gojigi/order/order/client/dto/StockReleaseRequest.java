package com.sesac.gojigi.order.order.client.dto;

import java.util.List;

/**
 * product 재고 해제 — POST /internal/stock/reservations/{sagaId}/release (product.md 2절, 주문 Saga 보상).
 * items 는 예약 때 보낸 목록 그대로 — 예약 기록이 없을 때 해제 표식을 남길 옵션을 알기 위해 쓴다.
 */
public record StockReleaseRequest(List<StockItem> items) {
}
