package com.sesac.gojigi.review.review.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 스토어 홈 · 관리자 스토어 목록 · 상세의 평점 · 리뷰 수 (review.md 10절) — 블라인드 · 삭제 제외 */
public record StoreRatingResponse(
        @Schema(description = "스토어 ID", example = "1") Long storeId,
        @Schema(description = "평균 별점 (소수 둘째 자리, 리뷰 없으면 0)", example = "4.71") double averageRating,
        @Schema(description = "리뷰 수", example = "128") long reviewCount) {
}
