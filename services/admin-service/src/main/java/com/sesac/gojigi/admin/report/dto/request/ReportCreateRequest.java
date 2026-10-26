package com.sesac.gojigi.admin.report.dto.request;

import com.sesac.gojigi.admin.report.entity.ReportReason;
import com.sesac.gojigi.admin.report.entity.ReportTargetType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 신고 접수 — 판매자 "관리자 검토 요청"도 같은 본문 (reasonCode SELLER_REQUEST) */
public record ReportCreateRequest(
        @NotNull ReportTargetType targetType,
        @NotNull @Positive Long targetId,
        @NotNull ReportReason reasonCode,
        @Size(max = 500) String detail) {
}
