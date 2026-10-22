package com.sesac.gojigi.order.order.client.dto;

/**
 * store 스토어 상세 (내부) — GET /internal/stores/{storeId} (docs/api/store.md 1절). 상품주문 스냅샷의 스토어명에 쓴다.
 * status 는 값이 늘어날 수 있어 문자열로 받는다 (ACTIVE · DORMANT · SUSPENDED · CLOSED).
 */
public record StoreResponse(Long storeId, Long userId, String storeName, String status) {
}
