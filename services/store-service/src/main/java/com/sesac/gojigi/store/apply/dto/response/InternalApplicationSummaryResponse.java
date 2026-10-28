package com.sesac.gojigi.store.apply.dto.response;

import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.entity.BizType;
import com.sesac.gojigi.store.apply.entity.ExpectedMonthlySales;
import com.sesac.gojigi.store.apply.entity.StoreApplication;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 관리자 입점 심사 목록 한 줄 — admin-service 가 categoryId 를 이름으로 바꿔 붙인다 (docs/api/store.md 3절). 필드는 추가만 한다 */
public record InternalApplicationSummaryResponse(
        @Schema(description = "신청 ID", example = "27")
        Long applicationId,

        @Schema(description = "접수번호 — 표시 전용", example = "AP-2610-027")
        String applicationNo,

        @Schema(description = "스토어명", example = "곳간상회")
        String storeName,

        @Schema(description = "대표자명", example = "홍길동")
        String ownerName,

        @Schema(description = "사업자 유형", example = "INDIVIDUAL")
        BizType bizType,

        @Schema(description = "주 카테고리 ID (상위) — 이름은 호출자가 붙임", example = "1")
        Long categoryId,

        @Schema(description = "예상 월 매출 구간 (선택 입력이라 null 가능)", example = "FROM_5M_TO_20M")
        ExpectedMonthlySales expectedMonthlySales,

        @Schema(description = "신청 상태", example = "PENDING")
        ApplicationStatus status,

        @Schema(description = "제출 시각", example = "2026-10-22T14:05:11+09:00")
        OffsetDateTime submittedAt,

        @Schema(description = "마지막 상태 변경 시각", example = "2026-10-22T14:05:11+09:00")
        OffsetDateTime statusChangedAt) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 제출 시각 = 필수 약관 동의 시각 (제출 때 같은 값으로 저장) */
    public static InternalApplicationSummaryResponse from(StoreApplication application) {
        return new InternalApplicationSummaryResponse(
                application.getId(),
                SellerApplyResponse.applicationNo(application.getId(), application.getTermsAgreedAt()),
                application.getStoreName(),
                application.getOwnerName(),
                application.getBizType(),
                application.getCategoryId(),
                application.getExpectedMonthlySales(),
                application.getStatus(),
                application.getTermsAgreedAt().atZone(KST).toOffsetDateTime(),
                application.getStatusChangedAt().atZone(KST).toOffsetDateTime());
    }
}
