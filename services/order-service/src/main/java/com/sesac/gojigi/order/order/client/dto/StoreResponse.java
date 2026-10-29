package com.sesac.gojigi.order.order.client.dto;

/**
 * store 스토어 상세 (내부) — GET /internal/stores/{storeId} (docs/api/store.md 1절). 상품주문 스냅샷의 스토어명 · 배송비 정책에 쓴다.
 * status 는 값이 늘어날 수 있어 문자열로 받는다 (ACTIVE · SUSPENDED · CLOSED). freeShippingThreshold 가 null 이면 무료배송 없음.
 */
public record StoreResponse(Long storeId, Long userId, String storeName, String status, long shippingFee,
                            Long freeShippingThreshold) {

    public ShippingPolicy shippingPolicy() {
        return new ShippingPolicy(shippingFee, freeShippingThreshold);
    }
}
