package com.sesac.gojigi.review.review.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 판매자 답글 작성 · 수정 (review.md 7절) — 리뷰당 1개라 같은 요청으로 덮어쓴다 */
public record ReviewReplyRequest(
        @Schema(description = "답글 — 일반 텍스트, 태그는 지우고 저장", example = "소중한 후기 감사합니다! 다음에도 만족하실 수 있도록 준비하겠습니다.")
        @NotBlank(message = "답글을 입력해 주세요.")
        @Size(max = 1000, message = "답글은 1,000자 이하로 입력해 주세요.")
        String content) {
}
