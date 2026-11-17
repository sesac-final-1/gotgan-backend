package com.sesac.gojigi.admin.system.controller;

import com.sesac.gojigi.admin.system.dto.response.IncidentReportResponses;
import com.sesac.gojigi.admin.system.service.IncidentReportService;
import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 장애 분석 리포트 (11/17) — 운영 지표 · 로그를 보여 주므로 게이트웨이 ADMIN 확인에 더해 여기서도 확인한다 */
@Tag(name = "관리자 장애 분석 리포트", description = "기간별 이슈 · 원인 · 시간대별 건수 — 인가: ADMIN")
@RestController
@RequestMapping("/api/admin/system/report")
@RequiredArgsConstructor
public class AdminIncidentReportController {

    private final IncidentReportService incidentReportService;

    @Operation(summary = "장애 분석 리포트", description = """
            from · to = ISO-8601 시각(예: 2026-11-16T09:00:00Z). 최근 7일 안, 길이 7일 이하(아니면 400 SYSTEM_REPORT_INVALID_RANGE).
            구간 = 3시간 이하 1분 · 24시간 이하 5분 · 3일 이하 15분 · 그 이상 1시간.
            summary(운영팀 한 문장) · buckets(구간별 건수) · issues(이슈 · 원인 · 근거 로그) · events(파드 · 배포 · 장애 주입 · 부하테스트) · suspicious(탐색 요청 로그)""")
    @GetMapping
    public ApiResponse<IncidentReportResponses.Report> report(@Parameter(hidden = true) @CurrentUser AuthUser admin,
                                                      @RequestParam Instant from, @RequestParam Instant to) {
        if (!admin.hasRole("ADMIN")) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return ApiResponse.success("장애 분석 리포트를 만들었습니다.", incidentReportService.report(from, to));
    }
}
