package com.sesac.gojigi.settlement.settlement.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.settlement.settlement.dto.response.ReportUrlResponse;
import com.sesac.gojigi.settlement.settlement.dto.response.SellerSettlementsResponse;
import com.sesac.gojigi.settlement.settlement.dto.response.SettlementDetailResponse;
import com.sesac.gojigi.settlement.settlement.service.SettlementQueryService;
import com.sesac.gojigi.settlement.settlement.service.SettlementReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 판매자 정산 내역 (STL-07 · 10) — 게이트웨이가 SELLER 만 통과시키고(/api/seller/** hasRole SELLER), 여기서는 X-Store-Id 로 내 스토어 것만.
 * 다른 스토어 정산서는 403 FORBIDDEN.
 */
@Tag(name = "판매자 정산", description = "인가: SELLER (소유권: X-Store-Id)")
@RestController
@RequestMapping("/api/seller/settlements")
@RequiredArgsConstructor
public class SellerSettlementController {

    private final SettlementQueryService settlementQueryService;
    private final SettlementReportService settlementReportService;

    @Operation(summary = "내 스토어 정산 목록 + 요약", description = "요약: 다음 지급 예정 · 최근 8주 지급완료 · 이번 주 정산 예정 · 적용 수수료율")
    @GetMapping
    public ApiResponse<SellerSettlementsResponse> list(@CurrentUser AuthUser user) {
        return ApiResponse.success("정산 내역을 조회했습니다.", settlementQueryService.listForStore(user.storeId()));
    }

    @Operation(summary = "정산 건별 상세", description = "건별 매출 · 수수료. 다른 스토어면 403")
    @GetMapping("/{id}")
    public ApiResponse<SettlementDetailResponse> detail(@CurrentUser AuthUser user, @PathVariable Long id) {
        return ApiResponse.success("정산 상세를 조회했습니다.", settlementQueryService.detailForStore(id, user.storeId()));
    }

    @Operation(summary = "정산 명세서 다운로드 링크", description = "지급 완료 건만 · presigned GET 10분(클릭할 때마다 발급). 지급 전 · CSV 생성 전 409 SETTLE_REPORT_NOT_READY")
    @GetMapping("/{id}/report")
    public ApiResponse<ReportUrlResponse> report(@CurrentUser AuthUser user, @PathVariable Long id) {
        return ApiResponse.success("명세서 링크를 만들었습니다.", settlementReportService.reportUrl(id, user.storeId()));
    }
}
