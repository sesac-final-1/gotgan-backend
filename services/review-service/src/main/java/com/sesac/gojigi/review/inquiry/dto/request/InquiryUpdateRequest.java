package com.sesac.gojigi.review.inquiry.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 내 문의 수정 (review.md 14절) — 등록과 같은 규칙, 내용 · 비밀글 여부를 통째로 바꾼다 (상품은 못 바꿈) */
public record InquiryUpdateRequest(
        @Schema(description = "문의 내용 — 일반 텍스트, 태그는 지우고 저장", example = "M이랑 L 중 어떤 걸 추천하시나요? 어깨가 넓은 편이에요.")
        @NotBlank(message = "문의 내용을 입력해 주세요.")
        @Size(max = 1000, message = "문의 내용은 1,000자 이하로 입력해 주세요.")
        String content,

        @Schema(description = "비밀글 — 작성자 · 판매자만 내용 확인 (생략하면 공개)", example = "true")
        Boolean secret) {

    /** 생략(null)은 공개글 */
    public InquiryUpdateRequest {
        secret = Boolean.TRUE.equals(secret);
    }
}
