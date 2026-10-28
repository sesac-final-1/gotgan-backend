package com.sesac.gojigi.review.review.dto.response;

import com.sesac.gojigi.review.review.entity.Review;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 판매자 답글 결과 — 화면이 해당 행만 바로 바꿀 수 있게 */
public record ReviewReplyResponse(
        @Schema(description = "리뷰 ID", example = "31")
        Long reviewId,

        @Schema(description = "저장된 답글 (태그를 지운 값)", example = "소중한 후기 감사합니다!")
        String replyContent,

        @Schema(description = "답글 작성 · 수정 시각", example = "2026-10-28T10:00:00+09:00")
        OffsetDateTime repliedAt) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static ReviewReplyResponse from(Review review) {
        return new ReviewReplyResponse(review.getId(), review.getReplyContent(),
                review.getRepliedAt().atZone(KST).toOffsetDateTime());
    }
}
