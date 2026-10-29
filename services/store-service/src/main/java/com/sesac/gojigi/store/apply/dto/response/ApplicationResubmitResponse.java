package com.sesac.gojigi.store.apply.dto.response;

import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** 보완 재제출 결과 (docs/api/store.md 2-3절) */
public record ApplicationResubmitResponse(
        @Schema(description = "신청 ID", example = "27")
        Long applicationId,

        @Schema(description = "신청 상태 — 항상 PENDING(심사대기)", example = "PENDING")
        ApplicationStatus status,

        @Schema(description = "재제출 시각", example = "2026-10-29T16:20:00+09:00")
        OffsetDateTime resubmittedAt) {
}
