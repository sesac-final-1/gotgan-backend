package com.sesac.gojigi.review.review.dto.response;

import com.sesac.gojigi.review.eligibility.entity.ReviewEligibility;
import com.sesac.gojigi.review.review.entity.Review;
import com.sesac.gojigi.review.review.entity.ReviewFit;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 마이페이지 내가 쓴 리뷰 한 건 (review.md 4절) — 프론트 MyReviews "내가 쓴 리뷰" 탭 */
public record MyReviewResponse(
        @Schema(description = "리뷰 ID", example = "31")
        Long reviewId,

        @Schema(description = "상품주문 ID", example = "11")
        Long orderItemId,

        @Schema(description = "상품 ID", example = "101")
        Long productId,

        @Schema(description = "구매확정 시점 상품명", example = "린넨 오버핏 셔츠")
        String productName,

        @Schema(description = "산 옵션 — 옵션 없는 상품 · 스냅샷 전 자격은 null", example = "오트밀 / M")
        String optionName,

        @Schema(description = "상품 대표 사진 — 스냅샷 전 자격은 null", example = "https://cdn.gotgan.click/products/1/a1b2.jpg")
        String thumbnailUrl,

        @Schema(description = "별점 1~5", example = "5")
        int rating,

        @Schema(description = "한줄 요약", example = "핏이 진짜 예뻐요")
        String title,

        @Schema(description = "본문", example = "평소 M 입는데 M 주문했고 어깨가 살짝 내려오는 오버핏이에요.")
        String content,

        @Schema(description = "사이즈 체감", example = "TRUE_TO_SIZE")
        ReviewFit fit,

        @Schema(description = "관리자 블라인드 — true 면 화면에 '관리자에 의해 블라인드 처리됨', 수정 불가", example = "false")
        boolean blinded,

        @Schema(description = "판매자 답글", example = "소중한 후기 감사합니다.")
        String replyContent,

        @Schema(description = "판매자 답글 시각", example = "2026-10-21T09:30:00+09:00")
        OffsetDateTime repliedAt,

        @Schema(description = "작성 시각", example = "2026-10-20T14:00:00+09:00")
        OffsetDateTime createdAt) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static MyReviewResponse from(Review review) {
        ReviewEligibility eligibility = review.getEligibility();
        return new MyReviewResponse(review.getId(), review.getOrderItemId(), review.getProductId(),
                eligibility.getProductName(), eligibility.getOptionName(), eligibility.getThumbnailUrl(),
                review.getRating(), review.getTitle(), review.getContent(), review.getFit(), review.isBlinded(),
                review.getReplyContent(), kst(review.getRepliedAt()), kst(review.getCreatedAt()));
    }

    private static OffsetDateTime kst(LocalDateTime time) {
        return time == null ? null : time.atZone(KST).toOffsetDateTime();
    }
}
