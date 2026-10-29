package com.sesac.gojigi.order.order.client.dto;

/**
 * 스토어 배송비 정책 — store 스토어 상세(StoreResponse)의 shippingFee · freeShippingThreshold (스펙 STR-06 · ORD-02).
 * 스토어별 묶음배송비 1회, 스토어 상품 합계가 freeThreshold 이상이면 무료. freeThreshold 가 null 이면 무료배송 없음.
 */
public record ShippingPolicy(long baseFee, Long freeThreshold) {

    public long feeFor(long storeProductAmt) {
        return freeThreshold != null && storeProductAmt >= freeThreshold ? 0L : baseFee;
    }

    /** 무료배송까지 더 담을 금액 — 무료배송이 없으면 0 */
    public long amountToFree(long storeProductAmt) {
        return freeThreshold == null ? 0L : Math.max(0L, freeThreshold - storeProductAmt);
    }
}
