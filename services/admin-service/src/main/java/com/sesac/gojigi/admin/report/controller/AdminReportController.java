package com.sesac.gojigi.admin.report.controller;

import com.sesac.gojigi.admin.report.dto.request.ReportResolveRequest;
import com.sesac.gojigi.admin.report.dto.response.ReportResponses;
import com.sesac.gojigi.admin.report.entity.ReportTargetType;
import com.sesac.gojigi.admin.report.service.AdminReportService;
import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 신고 관리 — 역할(ADMIN)은 게이트웨이가 확인한다 */
@Tag(name = "관리자 신고 관리", description = "대상별 신고 묶음 · 상세 · 처리(리뷰 블라인드 · 회원 정지 · 기각) — 인가: ADMIN")
@RestController
@RequestMapping("/api/admin/reports")
@RequiredArgsConstructor
public class AdminReportController {

    private final AdminReportService adminReportService;

    @Operation(summary = "신고 목록 (대상별 묶음)", description = "targetType(REVIEW/USER, 생략 = 전체) → 배열(페이징 없음) — 처리 대기 많은 순 → 최근 신고 순")
    @GetMapping
    public ApiResponse<List<ReportResponses.TargetSummary>> list(@RequestParam(required = false) ReportTargetType targetType) {
        return ApiResponse.success("신고 목록을 조회했습니다.", adminReportService.list(targetType));
    }

    @Operation(summary = "신고 상세", description = "신고 목록 · 조치 이력 + 대상 현재 값(review · user — 불러오지 못하면 target null + targetError)")
    @GetMapping("/{targetType}/{targetId}")
    public ApiResponse<ReportResponses.TargetDetail> detail(@Parameter(hidden = true) @CurrentUser AuthUser admin,
                                                            @PathVariable ReportTargetType targetType, @PathVariable Long targetId) {
        return ApiResponse.success("신고 상세를 조회했습니다.", adminReportService.detail(targetType, targetId, admin.userId()));
    }

    @Operation(summary = "신고 처리", description = """
            {action, reason, days?} — REVIEW: BLIND · UNBLIND, USER: SUSPEND(days 1~365, 생략 = 영구) · UNSUSPEND, 공통: DISMISS.
            대상 서비스 호출 성공 후 조치 기록 + 열린 신고 일괄 닫기. 400 REPORT_ACTION_NOT_ALLOWED · 대상 서비스 오류 그대로""")
    @PostMapping("/{targetType}/{targetId}/resolve")
    public ApiResponse<ReportResponses.Resolved> resolve(@Parameter(hidden = true) @CurrentUser AuthUser admin,
                                                         @PathVariable ReportTargetType targetType, @PathVariable Long targetId,
                                                         @Valid @RequestBody ReportResolveRequest request) {
        return ApiResponse.success("신고를 처리했습니다.", adminReportService.resolve(targetType, targetId, admin.userId(), request));
    }
}
