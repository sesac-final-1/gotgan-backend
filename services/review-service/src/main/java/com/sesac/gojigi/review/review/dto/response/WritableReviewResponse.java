package com.sesac.gojigi.review.review.dto.response;

import com.sesac.gojigi.review.eligibility.entity.ReviewEligibility;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 마이페이지 작성 가능한 리뷰 한 건 (review.md 5절) — 프론트 MyReviews "작성 가능한 리뷰" 탭 · 작성 모달 */
public record WritableReviewResponse(
        @Schema(description = "상품주문 ID — 리뷰 작성 orderItemId 로", example = "11")
        Long orderItemId,

        @Schema(description = "상품 ID", example = "101")
        Long productId,

        @Schema(description = "구매확정 시점 상품명", example = "린넨 오버핏 셔츠")
        String productName,

        @Schema(description = "산 옵션 — 옵션 없는 상품 · 스냅샷 전 자격은 null", example = "오트밀 / M")
        String optionName,

        @Schema(description = "상품 대표 사진 — 스냅샷 전 자격은 null", example = "https://cdn.gotgan.click/products/1/a1b2.jpg")
        String thumbnailUrl,

        @Schema(description = "구매확정 시각", example = "2026-10-20T14:00:00+09:00")
        OffsetDateTime confirmedAt,

        @Schema(description = "작성 기한 — 이 시각까지 작성 가능 (화면 D-day 계산)", example = "2026-11-19T14:00:00+09:00")
        OffsetDateTime expiresAt) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static WritableReviewResponse from(ReviewEligibility eligibility) {
        return new WritableReviewResponse(eligibility.getOrderItemId(), eligibility.getProductId(),
                eligibility.getProductName(), eligibility.getOptionName(), eligibility.getThumbnailUrl(),
                eligibility.getConfirmedAt().atZone(KST).toOffsetDateTime(),
                eligibility.getExpiresAt().atZone(KST).toOffsetDateTime());
    }
}
