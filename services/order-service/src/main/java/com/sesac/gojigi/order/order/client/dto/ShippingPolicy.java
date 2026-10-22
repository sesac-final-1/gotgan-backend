package com.sesac.gojigi.order.order.client.dto;

/**
 * 스토어 배송비 정책 — 스토어별 묶음배송비 1회, 스토어 상품 합계가 freeThreshold 이상이면 무료 (스펙 STR-06 · ORD-02).
 * TODO(store shipping-policy 계약 확정 시): GET /internal/stores/{id}/shipping-policy 응답 모양에 맞춘다.
 */
public record ShippingPolicy(long baseFee, long freeThreshold) {

    public long feeFor(long storeProductAmt) {
        return storeProductAmt >= freeThreshold ? 0L : baseFee;
    }
}
