package com.sesac.gojigi.order.order.client.dto;

import java.util.List;

/** product 재고 예약 · 해제 응답 — 해제할 예약이 없었으면 items 가 빈 목록 */
public record StockReservationResponse(String sagaId, List<StockItem> items) {
}
