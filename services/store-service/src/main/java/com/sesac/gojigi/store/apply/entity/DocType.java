package com.sesac.gojigi.store.apply.entity;

/**
 * 입점 서류 종류 — 업종과 무관하게 4종 (10/22 결정, 스펙 STR-02).
 * storedb V1 주석의 CATEGORY_CERT 는 쓰지 않아 enum 에 두지 않는다.
 */
public enum DocType {
    BUSINESS_REG,
    ECOMMERCE_REG,
    BANK_COPY,
    PRODUCT_PHOTO
}
