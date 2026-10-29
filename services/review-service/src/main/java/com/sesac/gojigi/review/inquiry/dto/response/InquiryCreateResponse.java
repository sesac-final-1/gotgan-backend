package com.sesac.gojigi.review.inquiry.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 상품 문의 등록 결과 */
public record InquiryCreateResponse(
        @Schema(description = "상품 문의 ID", example = "12")
        Long inquiryId) {
}
