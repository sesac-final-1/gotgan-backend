package com.sesac.gojigi.store.apply.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 입점 서류 종류 — 업종과 무관하게 4종 (10/22 결정, 스펙 STR-02).
 * storedb V1 주석의 CATEGORY_CERT 는 쓰지 않아 enum 에 두지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum DocType {
    BUSINESS_REG("사업자등록증"),
    ECOMMERCE_REG("통신판매업 신고증"),
    BANK_COPY("통장 사본"),
    PRODUCT_PHOTO("상품 사진");

    /** 보완요청 메일 · 화면 알림에 그대로 찍히는 이름 (store.md 7절 — 이벤트 requestedDocs) */
    private final String label;
}
