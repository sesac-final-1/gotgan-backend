package com.sesac.gojigi.review.inquiry.dto.response;

import com.sesac.gojigi.review.inquiry.entity.ProductInquiry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 내 문의 한 건 (review.md 14절) — 수정 결과. 상품명 · 대표 사진은 등록 시점 스냅샷 */
public record InquiryResponse(
        @Schema(description = "상품 문의 ID", example = "12")
        Long inquiryId,
        @Schema(description = "상품 ID", example = "101")
        Long productId,
        @Schema(description = "등록 시점 상품명", example = "린넨 오버핏 셔츠")
        String productName,
        @Schema(description = "등록 시점 상품 대표 사진 URL — 없으면 null")
        String thumbnailUrl,
        @Schema(description = "문의 내용", example = "M이랑 L 중 어떤 걸 추천하시나요?")
        String content,
        @Schema(description = "비밀글", example = "false")
        boolean secret,
        @Schema(description = "판매자 답변 — 없으면 null", example = "M 추천드립니다.")
        String answerContent,
        @Schema(description = "답변 시각 — 없으면 null", example = "2026-10-29T11:00:00+09:00")
        OffsetDateTime answeredAt,
        @Schema(description = "등록 시각", example = "2026-10-29T10:00:00+09:00")
        OffsetDateTime createdAt) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static InquiryResponse from(ProductInquiry inquiry) {
        return new InquiryResponse(inquiry.getId(), inquiry.getProductId(), inquiry.getProductName(),
                inquiry.getThumbnailUrl(), inquiry.getContent(), inquiry.isSecret(), inquiry.getAnswerContent(),
                kst(inquiry.getAnsweredAt()), kst(inquiry.getCreatedAt()));
    }

    private static OffsetDateTime kst(LocalDateTime time) {
        return time == null ? null : time.atZone(KST).toOffsetDateTime();
    }
}
