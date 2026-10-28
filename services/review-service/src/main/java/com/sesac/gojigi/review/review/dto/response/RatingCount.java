package com.sesac.gojigi.review.review.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 별점 분포 한 칸 — 판매자 평점 요약 · 상품 리뷰 요약 공용 */
public record RatingCount(
        @Schema(description = "별점", example = "5") int rating,
        @Schema(description = "리뷰 수", example = "23") long count) {
}
