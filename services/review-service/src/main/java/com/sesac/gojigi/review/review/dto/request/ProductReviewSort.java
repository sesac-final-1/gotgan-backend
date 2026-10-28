package com.sesac.gojigi.review.review.dto.request;

import org.springframework.data.domain.Sort;

/** 상품 상세 리뷰 정렬 (프론트 ShopProductReviews) — 도움순은 범위 제외(10/28), 같은 값이면 최신 · 나중 ID 먼저 */
public enum ProductReviewSort {
    LATEST(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),
    RATING_HIGH(Sort.by(Sort.Order.desc("rating"), Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),
    RATING_LOW(Sort.by(Sort.Order.asc("rating"), Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

    private final Sort sort;

    ProductReviewSort(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }
}
