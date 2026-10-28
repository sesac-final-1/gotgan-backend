package com.sesac.gojigi.review.review.dto.response;

import com.sesac.gojigi.review.eligibility.entity.ReviewEligibility;
import com.sesac.gojigi.review.review.entity.Review;
import com.sesac.gojigi.review.review.entity.ReviewFit;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/** 판매자 리뷰 관리 한 건 (review.md 6절) — 프론트 SellerReviewList 표 */
public record SellerReviewResponse(
        @Schema(description = "리뷰 ID", example = "31")
        Long reviewId,

        @Schema(description = "상품 ID", example = "101")
        Long productId,

        @Schema(description = "구매확정 시점 상품명", example = "린넨 오버핏 셔츠")
        String productName,

        @Schema(description = "산 옵션 — 옵션 없는 상품 · 스냅샷 전 자격은 null", example = "오트밀 / M")
        String optionName,

        @Schema(description = "작성자 공개 표시 이름 (닉네임, 없으면 마스킹 실명)", example = "곳간러버")
        String writerDisplayName,

        @Schema(description = "별점 1~5", example = "2")
        int rating,

        @Schema(description = "한줄 요약", example = "길이가 조금 길어요")
        String title,

        @Schema(description = "본문", example = "키 160인데 기장이 살짝 길어서 수선했어요.")
        String content,

        @Schema(description = "사이즈 체감", example = "LARGE")
        ReviewFit fit,

        @Schema(description = "관리자 블라인드 — true 면 '블라인드됨', 답글 불가", example = "false")
        boolean blinded,

        @Schema(description = "내 답글", example = "불편을 드려 죄송합니다.")
        String replyContent,

        @Schema(description = "답글 작성 · 수정 시각", example = "2026-10-21T09:30:00+09:00")
        OffsetDateTime repliedAt,

        @Schema(description = "작성 시각", example = "2026-10-20T14:00:00+09:00")
        OffsetDateTime createdAt,

        @Schema(description = "리뷰 사진 URL — 순서대로, 없으면 빈 목록")
        List<String> imageUrls) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static SellerReviewResponse from(Review review, List<String> imageUrls) {
        ReviewEligibility eligibility = review.getEligibility();
        return new SellerReviewResponse(review.getId(), review.getProductId(), eligibility.getProductName(),
                eligibility.getOptionName(), review.getWriterDisplayName(), review.getRating(), review.getTitle(),
                review.getContent(), review.getFit(), review.isBlinded(), review.getReplyContent(),
                kst(review.getRepliedAt()), kst(review.getCreatedAt()), imageUrls);
    }

    private static OffsetDateTime kst(LocalDateTime time) {
        return time == null ? null : time.atZone(KST).toOffsetDateTime();
    }
}
