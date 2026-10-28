package com.sesac.gojigi.review.review.dto.response;

import com.sesac.gojigi.review.review.entity.ReviewFit;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 상품 상세 리뷰 상단 — 평균 · 리뷰 수 · 별점 분포 · 사이즈 체감 분포 (review.md 9절). 블라인드 · 삭제 제외 */
public record ProductReviewSummaryResponse(
        @Schema(description = "평균 별점 (소수 둘째 자리, 리뷰 없으면 0)", example = "4.62")
        double averageRating,

        @Schema(description = "리뷰 수 — 리뷰 탭 숫자", example = "37")
        long reviewCount,

        @Schema(description = "별점 분포 5점 → 1점 (없는 별점도 0 으로)")
        List<RatingCount> distribution,

        @Schema(description = "사이즈 체감 분포 — 작아요 · 정사이즈 · 커요 순 3칸, 체감을 남긴 리뷰만 센다")
        List<FitCount> fits) {

    public record FitCount(
            @Schema(description = "사이즈 체감", example = "TRUE_TO_SIZE") ReviewFit fit,
            @Schema(description = "리뷰 수", example = "18") long count) {
    }
}
