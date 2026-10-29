package com.sesac.gojigi.store.store.dto.response;

import com.sesac.gojigi.store.store.entity.StoreStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** 판매자 폐점 결과 (docs/api/store.md 12절) */
public record StoreCloseResponse(
        @Schema(description = "스토어 ID", example = "1")
        Long storeId,

        @Schema(description = "스토어 상태 — 항상 CLOSED", example = "CLOSED")
        StoreStatus status,

        @Schema(description = "폐점 시각 — 이미 폐점이었으면 처음 폐점한 시각", example = "2026-10-29T15:00:00+09:00")
        OffsetDateTime closedAt) {
}
