package com.sesac.gojigi.review.review.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 리뷰 사진 한 장 — 작성자 화면(마이페이지 · 수정 결과)은 수정 때 기존 사진을 다시 보내야 해서 키도 준다 */
public record ReviewImageResponse(
        @Schema(description = "S3 키 — 수정 때 imageKeys 에 그대로", example = "reviews/4/7f3c9e2a-1b2c-4d5e-8f90-a1b2c3d4e5f6.jpg")
        String key,

        @Schema(description = "조회 URL", example = "https://sesac-gojigi-images-4380.s3.ap-northeast-2.amazonaws.com/reviews/4/7f3c9e2a-1b2c-4d5e-8f90-a1b2c3d4e5f6.jpg")
        String url) {
}
