package com.sesac.gojigi.review.review.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 판매자 리뷰 관리 상단 — 스토어 평균 · 별점 분포 · 상품별 평균 · 탭 숫자 (review.md 6절) */
public record SellerReviewSummaryResponse(
        @Schema(description = "스토어 평균 별점 (소수 둘째 자리, 리뷰 없으면 0) — 블라인드 · 삭제 제외", example = "4.62")
        double averageRating,

        @Schema(description = "평균에 들어간 리뷰 수 — 블라인드 · 삭제 제외", example = "37")
        long ratedCount,

        @Schema(description = "별점 분포 5점 → 1점 (없는 별점도 0 으로)")
        List<RatingCount> distribution,

        @Schema(description = "상품별 평균 · 리뷰 수 — 평균 높은순")
        List<ProductRating> products,

        @Schema(description = "탭 숫자 — 전체(블라인드 포함) · 답글 미작성 · 1~2점")
        TabCounts tabCounts) {

    public record RatingCount(
            @Schema(description = "별점", example = "5") int rating,
            @Schema(description = "리뷰 수", example = "23") long count) {
    }

    public record ProductRating(
            @Schema(description = "상품 ID", example = "101") Long productId,
            @Schema(description = "상품명 (구매확정 시점 스냅샷)", example = "린넨 오버핏 셔츠") String productName,
            @Schema(description = "평균 별점 (소수 둘째 자리)", example = "4.8") double averageRating,
            @Schema(description = "리뷰 수", example = "12") long count) {
    }

    public record TabCounts(
            @Schema(description = "전체", example = "39") long all,
            @Schema(description = "답글 미작성 (블라인드 제외)", example = "8") long noReply,
            @Schema(description = "별점 1~2", example = "3") long lowRating) {
    }
}
