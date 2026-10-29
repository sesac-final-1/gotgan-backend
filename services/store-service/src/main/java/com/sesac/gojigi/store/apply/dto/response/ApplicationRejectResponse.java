package com.sesac.gojigi.store.apply.dto.response;

import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 입점 반려 결과 (docs/api/store.md 6절) */
public record ApplicationRejectResponse(
        @Schema(description = "신청 ID", example = "27")
        Long applicationId,

        @Schema(description = "신청 상태 — 항상 REJECTED", example = "REJECTED")
        ApplicationStatus status,

        @Schema(description = "반려 시각 — 30일 재신청 제한의 기준", example = "2026-10-28T14:30:00+09:00")
        OffsetDateTime decidedAt) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static ApplicationRejectResponse of(Long applicationId, ApplicationStatus status, LocalDateTime decidedAt) {
        return new ApplicationRejectResponse(applicationId, status, decidedAt.atZone(KST).toOffsetDateTime());
    }
}
