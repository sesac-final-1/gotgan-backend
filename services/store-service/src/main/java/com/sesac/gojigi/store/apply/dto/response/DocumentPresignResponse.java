package com.sesac.gojigi.store.apply.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** 입점 서류 업로드 주소 — 브라우저가 uploadUrl 로 PUT 한 뒤 key 를 신청 documents[].key 로 보낸다 (docs/api/store.md 2-1절) */
public record DocumentPresignResponse(
        @Schema(description = "S3 키 — 신청 때 documents[].key", example = "applications/4/3f2a0c5e-7d1b-4c1e-9a1f-0b2c3d4e5f60.pdf")
        String key,

        @Schema(description = "presigned PUT 주소 — Content-Type · 크기를 발급 요청과 같게 보내야 함")
        String uploadUrl,

        @Schema(description = "주소 만료 시각 (10분)", example = "2026-10-26T15:10:00+09:00")
        OffsetDateTime expiresAt) {
}
