package com.sesac.gojigi.admin.report.controller;

import com.sesac.gojigi.admin.report.dto.request.ReportCreateRequest;
import com.sesac.gojigi.admin.report.dto.response.ReportResponses;
import com.sesac.gojigi.admin.report.service.ReportService;
import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 신고 접수 — 게이트웨이 /api/reports/** → admin-service, 역할 BUYER · SELLER */
@Tag(name = "신고", description = "회원 · 리뷰 신고 접수 (판매자 검토 요청 포함) — 인가: BUYER · SELLER")
@RestController
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @Operation(summary = "신고 접수", description = """
            {targetType: REVIEW/USER, targetId, reasonCode, detail?} → 201 {reportId, status: OPEN}.
            판매자 검토 요청은 reasonCode SELLER_REQUEST(SELLER · 내 스토어 리뷰만). 409 REPORT_DUPLICATED · 400 REPORT_SELF ·
            REPORT_REASON_NOT_ALLOWED · 403 REPORT_NOT_OWN_STORE · 대상이 없으면 review · user 의 404 그대로""")
    @PostMapping("/api/reports")
    public ResponseEntity<ApiResponse<ReportResponses.Created>> create(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                                       @Valid @RequestBody ReportCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("신고를 접수했습니다.", reportService.create(user, request)));
    }
}
