package com.sesac.gojigi.review.inquiry.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 판매자 문의 탭 숫자 (review.md 19절) — 문의 화면 탭 · 판매자 메뉴 배지 · 대시보드 "미답변 문의". 삭제 제외 */
public record SellerInquirySummaryResponse(
        @Schema(description = "미답변", example = "3") long unanswered,
        @Schema(description = "답변완료", example = "12") long answered,
        @Schema(description = "전체", example = "15") long all) {
}
