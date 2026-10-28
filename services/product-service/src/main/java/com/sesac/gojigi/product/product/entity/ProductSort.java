package com.sesac.gojigi.product.product.entity;

/** 공개 상품 목록 정렬 (product.md 9절) — 같은 값이면 productId 큰 순(페이지가 바뀌어도 순서가 흔들리지 않게) */
public enum ProductSort {
    /** 신상품 — 등록일 최신 순 */
    NEW,
    /** 낮은 가격 — 판매가 오름차순 */
    PRICE_ASC,
    /** 높은 가격 — 판매가 내림차순 */
    PRICE_DESC
}
