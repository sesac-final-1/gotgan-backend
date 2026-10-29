package com.sesac.gojigi.store.apply.dto.response;

import com.sesac.gojigi.store.apply.dto.response.InternalApplicationDetailResponse.History;
import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.entity.DocType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/** 내 입점 신청 상태 — 신청자 화면의 접수 타임라인 · 보완요청 · 반려 안내 (docs/api/store.md 2-2절, STR-08). 필드는 추가만 한다 */
public record MyApplicationResponse(
        @Schema(description = "신청 ID", example = "27")
        Long applicationId,

        @Schema(description = "접수번호 — 표시 전용", example = "AP-2610-027")
        String applicationNo,

        @Schema(description = "신청 상태", example = "SUPPLEMENT")
        ApplicationStatus status,

        @Schema(description = "신청한 스토어명", example = "곳간상회")
        String storeName,

        @Schema(description = "신청한 스토어 주소", example = "gotgan-store")
        String slug,

        @Schema(description = "반려 · 보완요청 사유 (그 밖에는 null)", example = "통신판매업 신고증 사본이 흐려 확인이 어렵습니다")
        String statusReason,

        @Schema(description = "제출 시각", example = "2026-10-22T14:05:11+09:00")
        OffsetDateTime submittedAt,

        @Schema(description = "마지막 상태 변경 시각", example = "2026-10-23T10:00:00+09:00")
        OffsetDateTime statusChangedAt,

        @Schema(description = "보완 기한 — 보완요청일 때만, 지나면 자동 반려", example = "2026-10-30T10:00:00+09:00")
        OffsetDateTime supplementDueAt,

        @Schema(description = "재신청 가능 시각 — 반려일 때만(반려 + 30일)", example = "2026-11-22T10:00:00+09:00")
        OffsetDateTime reapplyAvailableAt,

        @Schema(description = "만들어진 스토어 ID — 승인일 때만", example = "3")
        Long storeId,

        @Schema(description = "상태 이력 — 오래된 순 (접수 null → PENDING 부터)")
        List<History> history,

        @Schema(description = "다시 낼 서류 종류 — 보완요청일 때만(서류가 아닌 내용 보완이면 [], 10/29 전 보완요청은 null)", example = "[\"ECOMMERCE_REG\"]")
        List<DocType> requestedDocs) {
}
