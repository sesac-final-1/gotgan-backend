package com.sesac.gojigi.review.inquiry.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 판매자 답변 작성 · 수정 (review.md 20절) — 문의당 1개라 같은 요청으로 덮어쓴다 */
public record InquiryAnswerRequest(
        @Schema(description = "답변 — 일반 텍스트, 태그는 지우고 저장", example = "M 추천드립니다! 오버핏이라 L 은 많이 클 수 있어요.")
        @NotBlank(message = "답변을 입력해 주세요.")
        @Size(max = 1000, message = "답변은 1,000자 이하로 입력해 주세요.")
        String content) {
}
