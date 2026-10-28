package com.sesac.gojigi.review.review.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 리뷰 작성 결과 */
public record ReviewCreateResponse(
        @Schema(description = "리뷰 ID", example = "31")
        Long reviewId) {
}
