package com.sesac.gojigi.product.stock.dto.response;

import com.sesac.gojigi.product.stock.dto.request.StockItem;
import java.util.List;

/** 재고 예약 · 해제 응답 (product.md 1 · 2절) — items 는 옵션 ID 오름차순. 해제할 예약이 없었으면 빈 목록 */
public record StockReservationResponse(String sagaId, List<StockItem> items) {
}
