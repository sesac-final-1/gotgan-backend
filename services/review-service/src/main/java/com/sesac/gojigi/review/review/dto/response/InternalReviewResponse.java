package com.sesac.gojigi.review.review.dto.response;

import com.sesac.gojigi.review.review.entity.Review;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/** admin 신고 처리 화면용 리뷰 (review.md 8절, 신고 B안 4-2 계약 — 필드를 바꾸면 admin ReviewReader 와 함께) */
public record InternalReviewResponse(
        @Schema(description = "리뷰 ID", example = "31")
        Long reviewId,

        @Schema(description = "상품 ID", example = "101")
        Long productId,

        @Schema(description = "스토어 ID — admin 이 판매자 검토 요청의 내 스토어 여부 확인", example = "1")
        Long storeId,

        @Schema(description = "작성자 회원 ID — admin 이 본인 신고 여부 확인", example = "4")
        Long userId,

        @Schema(description = "별점 1~5", example = "1")
        int rating,

        @Schema(description = "본문", example = "…")
        String content,

        @Schema(description = "사진 URL — 사진 업로드(REV-03) 전이라 지금은 항상 빈 목록")
        List<String> imageUrls,

        @Schema(description = "관리자 블라인드 여부", example = "false")
        boolean blinded,

        @Schema(description = "블라인드 사유 (해제면 null)", example = "null")
        String blindReason,

        @Schema(description = "작성 시각", example = "2026-10-20T14:00:00+09:00")
        OffsetDateTime createdAt) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static InternalReviewResponse from(Review review) {
        return new InternalReviewResponse(review.getId(), review.getProductId(), review.getStoreId(), review.getUserId(),
                review.getRating(), review.getContent(), List.of(), review.isBlinded(), review.getBlindReason(),
                review.getCreatedAt().atZone(KST).toOffsetDateTime());
    }
}
