package com.sesac.gojigi.review.inquiry.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 상품 문의 등록 (review.md 13절) — 프론트 ShopProductQna 문의하기 모달: 내용 · 비밀글 */
public record InquiryCreateRequest(
        @Schema(description = "문의할 상품 ID", example = "101")
        @NotNull(message = "문의할 상품을 선택해 주세요.")
        Long productId,

        @Schema(description = "문의 내용 — 일반 텍스트, 태그는 지우고 저장", example = "키 165에 55kg인데 M이랑 L 중 어떤 걸 추천하시나요?")
        @NotBlank(message = "문의 내용을 입력해 주세요.")
        @Size(max = 1000, message = "문의 내용은 1,000자 이하로 입력해 주세요.")
        String content,

        @Schema(description = "비밀글 — 작성자 · 판매자만 내용 확인 (생략하면 공개)", example = "false")
        Boolean secret) {

    /** 생략(null)은 공개글 */
    public InquiryCreateRequest {
        secret = Boolean.TRUE.equals(secret);
    }
}
