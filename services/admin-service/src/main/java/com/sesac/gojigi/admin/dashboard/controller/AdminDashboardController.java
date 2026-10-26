package com.sesac.gojigi.admin.dashboard.controller;

import com.sesac.gojigi.admin.dashboard.service.AdminDashboardService;
import com.sesac.gojigi.admin.dashboard.service.AdminDashboardService.Dashboard;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 대시보드 (ADM-02) — 서비스 통계 조합, 일부 실패는 부분 응답 */
@Tag(name = "관리자 대시보드", description = "settlement · order 통계 조합 — 인가: ADMIN")
@RestController
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;

    @Operation(summary = "관리자 대시보드", description = """
            {settlement: {scheduledCount, scheduledAmt, heldCount, heldAmt, paidThisMonthAmt, commissionRevenueThisMonth} | null,
             order: {totalTradeAmt, orderCount} | null, unavailable: [실패한 영역]} — 일부 실패해도 200""")
    @GetMapping("/api/admin/dashboard")
    public ApiResponse<Dashboard> dashboard() {
        return ApiResponse.success("대시보드를 조회했습니다.", adminDashboardService.dashboard());
    }
}
