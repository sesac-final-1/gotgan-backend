package com.sesac.gojigi.review.review.dto.response;

import com.sesac.gojigi.review.review.entity.Review;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 관리자 회원 상세 "작성 리뷰" 한 건 (review.md 8-3, 프론트 admin UserDetail) — 블라인드 포함 */
public record InternalUserReviewResponse(
        @Schema(description = "리뷰 ID", example = "31")
        Long reviewId,

        @Schema(description = "상품 ID", example = "101")
        Long productId,

        @Schema(description = "구매확정 시점 상품명", example = "린넨 셔츠")
        String productName,

        @Schema(description = "스토어 ID", example = "1")
        Long storeId,

        @Schema(description = "별점 1~5", example = "1")
        int rating,

        @Schema(description = "본문", example = "…")
        String content,

        @Schema(description = "관리자 블라인드 여부", example = "true")
        boolean blinded,

        @Schema(description = "블라인드 사유 (해제면 null)", example = "욕설 · 비방")
        String blindReason,

        @Schema(description = "작성 시각", example = "2026-10-20T14:00:00+09:00")
        OffsetDateTime createdAt) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static InternalUserReviewResponse from(Review review) {
        return new InternalUserReviewResponse(review.getId(), review.getProductId(),
                review.getEligibility().getProductName(), review.getStoreId(), review.getRating(), review.getContent(),
                review.isBlinded(), review.getBlindReason(), review.getCreatedAt().atZone(KST).toOffsetDateTime());
    }
}
