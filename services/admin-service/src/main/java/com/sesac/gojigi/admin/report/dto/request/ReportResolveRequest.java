package com.sesac.gojigi.admin.report.dto.request;

import com.sesac.gojigi.admin.report.entity.ModerationActionType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 관리자 처리 — days 는 SUSPEND 에만(생략 = 영구, user 정지 API 와 같은 1~365) */
public record ReportResolveRequest(
        @NotNull ModerationActionType action,
        @NotBlank @Size(max = 255) String reason,
        @Min(1) @Max(365) Integer days) {
}
