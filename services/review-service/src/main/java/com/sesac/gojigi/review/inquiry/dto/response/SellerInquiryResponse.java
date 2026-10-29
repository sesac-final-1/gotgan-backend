package com.sesac.gojigi.review.inquiry.dto.response;

import com.sesac.gojigi.review.inquiry.entity.ProductInquiry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 판매자 문의 한 건 (review.md 18절) — 프론트 SellerQnaList. 내 스토어 문의라 비밀글도 내용을 보여 준다 */
public record SellerInquiryResponse(
        @Schema(description = "상품 문의 ID", example = "12")
        Long inquiryId,
        @Schema(description = "상품 ID", example = "101")
        Long productId,
        @Schema(description = "등록 시점 상품명", example = "린넨 오버핏 셔츠")
        String productName,
        @Schema(description = "작성자 공개 표시 이름 (닉네임, 없으면 가운데 가린 실명)", example = "곳간러버")
        String writerDisplayName,
        @Schema(description = "문의 내용", example = "M이랑 L 중 어떤 걸 추천하시나요?")
        String content,
        @Schema(description = "비밀글 — 화면 자물쇠 표시", example = "true")
        boolean secret,
        @Schema(description = "내 답변 — 없으면 null(미답변)", example = "M 추천드립니다.")
        String answerContent,
        @Schema(description = "답변 시각 — 없으면 null", example = "2026-10-29T11:00:00+09:00")
        OffsetDateTime answeredAt,
        @Schema(description = "등록 시각", example = "2026-10-29T10:00:00+09:00")
        OffsetDateTime createdAt) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static SellerInquiryResponse from(ProductInquiry inquiry) {
        return new SellerInquiryResponse(inquiry.getId(), inquiry.getProductId(), inquiry.getProductName(),
                inquiry.getWriterDisplayName(), inquiry.getContent(), inquiry.isSecret(), inquiry.getAnswerContent(),
                kst(inquiry.getAnsweredAt()), kst(inquiry.getCreatedAt()));
    }

    private static OffsetDateTime kst(LocalDateTime time) {
        return time == null ? null : time.atZone(KST).toOffsetDateTime();
    }
}
