package com.sesac.gojigi.review.review.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 상품 카드 · 목록 · 판매자 상품 표의 별점 · 리뷰 수 (review.md 10절) — 블라인드 · 삭제 제외 */
public record ProductRatingResponse(
        @Schema(description = "상품 ID", example = "101") Long productId,
        @Schema(description = "평균 별점 (소수 둘째 자리, 리뷰 없으면 0)", example = "4.62") double averageRating,
        @Schema(description = "리뷰 수", example = "37") long reviewCount) {
}
