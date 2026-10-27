package com.sesac.gojigi.product.image.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** 상품 이미지 업로드 주소 — 브라우저가 uploadUrl 로 PUT 한 뒤 key 를 등록 · 수정 imageKeys 로 보낸다 (product.md 17절) */
public record ImagePresignResponse(
        @Schema(description = "S3 키 — 등록 · 수정 때 imageKeys", example = "products/1/7f3c9e2a-1b2c-4d5e-8f90-a1b2c3d4e5f6.jpg")
        String key,

        @Schema(description = "presigned PUT 주소 — Content-Type · 크기를 발급 요청과 같게 보내야 함")
        String uploadUrl,

        @Schema(description = "주소 만료 시각 (10분)", example = "2026-10-27T15:10:00+09:00")
        OffsetDateTime expiresAt) {
}
