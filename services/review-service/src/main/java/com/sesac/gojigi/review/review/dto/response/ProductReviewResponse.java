package com.sesac.gojigi.review.review.dto.response;

import com.sesac.gojigi.review.review.entity.Review;
import com.sesac.gojigi.review.review.entity.ReviewFit;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 상품 상세 공개 리뷰 한 건 (review.md 9절) — 프론트 ShopProductReviews. 작성자는 표시 이름만(회원 ID 없음) */
public record ProductReviewResponse(
        @Schema(description = "리뷰 ID", example = "31")
        Long reviewId,

        @Schema(description = "작성자 공개 표시 이름 (닉네임, 없으면 마스킹 실명) — 화면은 그대로 표시", example = "곳간러버")
        String writerDisplayName,

        @Schema(description = "별점 1~5", example = "5")
        int rating,

        @Schema(description = "한줄 요약", example = "핏이 진짜 예뻐요")
        String title,

        @Schema(description = "본문", example = "평소 M 입는데 M 주문했고 어깨가 살짝 내려오는 오버핏이에요.")
        String content,

        @Schema(description = "산 옵션 — 옵션 없는 상품 · 스냅샷 전 자격은 null", example = "오트밀 / M")
        String optionName,

        @Schema(description = "사이즈 체감", example = "TRUE_TO_SIZE")
        ReviewFit fit,

        @Schema(description = "판매자 답글", example = "소중한 후기 감사합니다.")
        String replyContent,

        @Schema(description = "판매자 답글 시각", example = "2026-10-21T09:30:00+09:00")
        OffsetDateTime repliedAt,

        @Schema(description = "작성 시각", example = "2026-10-20T14:00:00+09:00")
        OffsetDateTime createdAt) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static ProductReviewResponse from(Review review) {
        return new ProductReviewResponse(review.getId(), review.getWriterDisplayName(), review.getRating(),
                review.getTitle(), review.getContent(), review.getEligibility().getOptionName(), review.getFit(),
                review.getReplyContent(), kst(review.getRepliedAt()), kst(review.getCreatedAt()));
    }

    private static OffsetDateTime kst(LocalDateTime time) {
        return time == null ? null : time.atZone(KST).toOffsetDateTime();
    }
}
