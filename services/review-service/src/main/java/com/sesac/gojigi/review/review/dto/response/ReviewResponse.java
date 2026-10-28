package com.sesac.gojigi.review.review.dto.response;

import com.sesac.gojigi.review.review.entity.Review;
import com.sesac.gojigi.review.review.entity.ReviewFit;
import io.swagger.v3.oas.annotations.media.Schema;

/** 수정한 리뷰 — 화면이 목록 캐시를 다시 부르지 않고 바로 바꿀 수 있게 수정 가능한 값만 */
public record ReviewResponse(
        @Schema(description = "리뷰 ID", example = "31")
        Long reviewId,

        @Schema(description = "별점 1~5", example = "4")
        int rating,

        @Schema(description = "한줄 요약", example = "길이가 조금 길어요")
        String title,

        @Schema(description = "본문 (태그를 지운 값)", example = "키 160인데 기장이 살짝 길어서 수선했어요. 그 외에는 만족합니다.")
        String content,

        @Schema(description = "사이즈 체감", example = "LARGE")
        ReviewFit fit) {

    public static ReviewResponse from(Review review) {
        return new ReviewResponse(review.getId(), review.getRating(), review.getTitle(), review.getContent(), review.getFit());
    }
}
