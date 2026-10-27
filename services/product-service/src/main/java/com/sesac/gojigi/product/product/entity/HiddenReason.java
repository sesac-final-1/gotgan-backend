package com.sesac.gojigi.product.product.entity;

/**
 * 쇼핑몰에 안 보이는 이유 (product.md 11절 hiddenReason). 상품 status 는 그대로 두고 조회 때마다 계산한다 (10/20 결정).
 * 여러 이유가 겹치면 판매자가 직접 풀 수 있는 것부터 — 판매 상태 → 카테고리 → 스토어 순.
 */
public enum HiddenReason {
    STOPPED,
    BLOCKED,
    CATEGORY_INACTIVE,
    STORE_INACTIVE;

    /** 보이면 null. categoryActive = 하위 · 상위 카테고리 모두 사용 중 */
    public static HiddenReason of(Product product, boolean categoryActive) {
        if (product.getStatus() == ProductStatus.STOPPED) {
            return STOPPED;
        }
        if (product.getStatus() == ProductStatus.BLOCKED) {
            return BLOCKED;
        }
        if (!categoryActive) {
            return CATEGORY_INACTIVE;
        }
        if (!product.isStoreActive()) {
            return STORE_INACTIVE;
        }
        return null;
    }
}
