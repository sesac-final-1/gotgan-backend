package com.sesac.gojigi.store.dashboard.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.store.dashboard.service.SellerDashboardService;
import com.sesac.gojigi.store.dashboard.service.SellerDashboardService.Dashboard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 판매자 대시보드 (STR-07, docs/api/store.md 15절) — 역할 확인은 게이트웨이(/api/seller/** = SELLER), X-Store-Id 스토어 통계만 */
@Tag(name = "판매자 대시보드", description = "order · product · settlement 통계 조합 — 인가: SELLER")
@RestController
@RequiredArgsConstructor
public class SellerDashboardController {

    private final SellerDashboardService sellerDashboardService;

    @Operation(summary = "판매자 대시보드",
            description = """
            인가: SELLER (스토어 = X-Store-Id). 하위 통계를 그대로 담는다 —
            order: {storeId, toConfirmCount, toShipCount, claimCount, inProgressCount, todaySalesAmt} | null,
            orderDaily: [{date, tradeAmt, orderCount}](오늘 포함 최근 30일, 오래된 날부터) | null,
            product: {onSaleCount, lowStockCount, soldOutCount, stoppedCount, lowStockProducts[]} | null,
            settlement: {pendingItemCount, pendingPayoutAmt, nextPayout, recentPaid[]} | null, unavailable: [실패한 영역].
            일부 서비스가 실패해도 200 — 그 영역만 null. 정지 · 폐점 스토어도 그대로 조회된다.
            에러: 401 UNAUTHORIZED · 403 FORBIDDEN(스토어 없음)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공 — 일부 영역 실패 포함"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN — X-Store-Id 없음")
    })
    @GetMapping("/api/seller/dashboard")
    public ResponseEntity<ApiResponse<Dashboard>> dashboard(@Parameter(hidden = true) @CurrentUser AuthUser user) {
        if (user.storeId() == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);   // 스토어가 없는 판매자 — 판매자 스토어 API 와 같음
        }
        return ResponseEntity.ok(ApiResponse.success("대시보드를 조회했습니다.", sellerDashboardService.dashboard(user.storeId())));
    }
}
