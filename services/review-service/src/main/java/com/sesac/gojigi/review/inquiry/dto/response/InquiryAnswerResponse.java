package com.sesac.gojigi.review.inquiry.dto.response;

import com.sesac.gojigi.review.inquiry.entity.ProductInquiry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 판매자 답변 결과 — 화면이 해당 행만 바로 바꿀 수 있게 */
public record InquiryAnswerResponse(
        @Schema(description = "상품 문의 ID", example = "12")
        Long inquiryId,

        @Schema(description = "저장된 답변 (태그를 지운 값)", example = "M 추천드립니다!")
        String answerContent,

        @Schema(description = "답변 작성 · 수정 시각", example = "2026-10-29T11:00:00+09:00")
        OffsetDateTime answeredAt) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static InquiryAnswerResponse from(ProductInquiry inquiry) {
        return new InquiryAnswerResponse(inquiry.getId(), inquiry.getAnswerContent(),
                inquiry.getAnsweredAt().atZone(KST).toOffsetDateTime());
    }
}
