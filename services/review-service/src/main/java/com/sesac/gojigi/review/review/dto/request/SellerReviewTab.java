package com.sesac.gojigi.review.review.dto.request;

/** 판매자 리뷰 관리 탭 (프론트 SellerReviewList) */
public enum SellerReviewTab {
    /** 전체 (블라인드 포함) */
    ALL,
    /** 답글 미작성 — 답글을 달 수 있는 것(블라인드 제외)만 */
    NO_REPLY,
    /** 별점 1~2 */
    LOW_RATING,
    /** 사진 있는 리뷰 */
    PHOTO
}
