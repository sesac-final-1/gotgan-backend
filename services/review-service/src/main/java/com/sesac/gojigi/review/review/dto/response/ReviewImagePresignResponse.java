package com.sesac.gojigi.review.review.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** 리뷰 사진 업로드 주소 — 브라우저가 uploadUrl 로 PUT 한 뒤 key 를 작성 · 수정 imageKeys 로 보낸다 (review.md 12절) */
public record ReviewImagePresignResponse(
        @Schema(description = "S3 키 — 작성 · 수정 때 imageKeys", example = "reviews/4/7f3c9e2a-1b2c-4d5e-8f90-a1b2c3d4e5f6.jpg")
        String key,

        @Schema(description = "presigned PUT 주소 — Content-Type · 크기를 발급 요청과 같게 보내야 함")
        String uploadUrl,

        @Schema(description = "주소 만료 시각 (10분)", example = "2026-10-28T15:10:00+09:00")
        OffsetDateTime expiresAt) {
}
