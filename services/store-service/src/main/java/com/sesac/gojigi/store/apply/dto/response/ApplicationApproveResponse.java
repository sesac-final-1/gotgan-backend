package com.sesac.gojigi.store.apply.dto.response;

import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 입점 승인 결과 (docs/api/store.md 5절) */
public record ApplicationApproveResponse(
        @Schema(description = "신청 ID", example = "27")
        Long applicationId,

        @Schema(description = "신청 상태 — 항상 APPROVED", example = "APPROVED")
        ApplicationStatus status,

        @Schema(description = "새로 만든 스토어 ID", example = "3")
        Long storeId,

        @Schema(description = "승인 시각", example = "2026-10-28T14:30:00+09:00")
        OffsetDateTime decidedAt) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static ApplicationApproveResponse of(Long applicationId, ApplicationStatus status, Long storeId,
            LocalDateTime decidedAt) {
        return new ApplicationApproveResponse(applicationId, status, storeId, decidedAt.atZone(KST).toOffsetDateTime());
    }
}
