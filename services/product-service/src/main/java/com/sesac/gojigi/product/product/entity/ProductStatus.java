package com.sesac.gojigi.product.product.entity;

/**
 * 상품 판매 상태. 품절은 상태가 아니다 — ON_SALE 이면서 옵션 재고 합이 0 이면 화면에서 품절로 보인다.
 */
public enum ProductStatus {
    /** 판매중 */
    ON_SALE,
    /** 판매자 판매중지 — 판매자가 다시 켤 수 있음 */
    STOPPED,
    /** 관리자 강제 판매중지 — 판매자는 못 켜고, 관리자가 해제하면 STOPPED */
    BLOCKED
}
