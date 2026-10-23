package com.sesac.gojigi.store.apply.dto.response;

import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 입점 신청 접수 결과 — 접수 완료 화면 (docs/api/store.md 2절) */
public record SellerApplyResponse(
        @Schema(description = "신청 ID — API 식별에 쓰는 값", example = "27")
        Long applicationId,

        @Schema(description = "접수번호 — 표시 전용", example = "AP-2610-027")
        String applicationNo,

        @Schema(description = "신청 상태", example = "PENDING")
        ApplicationStatus status,

        @Schema(description = "제출 시각", example = "2026-10-22T14:05:11+09:00")
        OffsetDateTime submittedAt) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyMM");

    public static SellerApplyResponse of(Long applicationId, ApplicationStatus status, LocalDateTime submittedAt) {
        return new SellerApplyResponse(applicationId, applicationNo(applicationId, submittedAt), status,
                submittedAt.atZone(KST).toOffsetDateTime());
    }

    /** 접수번호 AP-{제출 연월}-{신청 ID 3자리 이상} — 저장하지 않고 계산 (10/22 설계 정리 6) */
    public static String applicationNo(Long applicationId, LocalDateTime submittedAt) {
        return "AP-%s-%03d".formatted(submittedAt.format(YEAR_MONTH), applicationId);
    }
}
