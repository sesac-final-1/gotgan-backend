package com.sesac.gojigi.store.apply.dto.response;

import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** 입점 보완요청 결과 (docs/api/store.md 7절) */
public record ApplicationSupplementResponse(
        @Schema(description = "신청 ID", example = "27")
        Long applicationId,

        @Schema(description = "신청 상태 — 항상 SUPPLEMENT", example = "SUPPLEMENT")
        ApplicationStatus status,

        @Schema(description = "재제출 기한 — 보완요청 시각 + 7일, 지나면 자동 반려", example = "2026-11-04T14:30:00+09:00")
        OffsetDateTime dueAt) {
}
