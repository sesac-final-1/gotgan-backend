package com.sesac.gojigi.store.store.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** 대표 이미지 업로드 주소 (docs/api/store.md 13-3절) — 올린 뒤 key 를 13-2절 imageKey 로 */
public record StoreImagePresignResponse(
        @Schema(description = "S3 키 — 설정 수정의 imageKey", example = "stores/1/3f2a0c5e-7d1b-4c1e-9a1f-0b2c3d4e5f60.jpg")
        String key,

        @Schema(description = "presigned PUT 주소 — Content-Type · 크기를 발급 요청과 같게 보내야 함")
        String uploadUrl,

        @Schema(description = "주소 만료 시각 (10분)", example = "2026-10-29T15:10:00+09:00")
        OffsetDateTime expiresAt) {
}
