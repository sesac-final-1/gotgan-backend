package com.sesac.gojigi.order.stats.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.order.stats.dto.response.DailyOrderStatsResponse;
import com.sesac.gojigi.order.stats.dto.response.PlatformOrderStatsResponse;
import com.sesac.gojigi.order.stats.dto.response.StoreOrderStatsResponse;
import com.sesac.gojigi.order.stats.service.OrderStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 서비스 간 전용 (store 판매자 대시보드 · 폐점 검사, admin 관리자 대시보드) — 게이트웨이 라우트 없음, 클러스터 내부에서만 호출 */
@Tag(name = "주문 통계 (내부)", description = "store-service · admin-service 대시보드 조합이 호출 — 인가: 내부")
@RestController
@RequiredArgsConstructor
public class InternalOrderStatsController {

    private final OrderStatsService orderStatsService;

    @Operation(summary = "스토어 주문 통계 (판매자 대시보드 · 폐점 검사)", description = """
            toConfirmCount 발주확인 대기(PAYMENT_DONE) · toShipCount 송장 대기(PREPARING) · claimCount 진행 중 클레임(REQUESTED · APPROVED).
            inProgressCount = 진행 중 상품주문 수 — PAYMENT_DONE · PREPARING · SHIPPING · DELIVERED · CANCEL_REQUESTED · RETURN_REQUESTED ·
            EXCHANGE_REQUESTED 이거나 진행 중 클레임(환불 미완료 취소 포함)이 걸린 상품주문. 1 이상이면 store 가 폐점을 거절한다.
            todaySalesAmt = 오늘(KST) 결제된 상품주문 금액 합계, 취소 · 반품 완료 제외. 없는 스토어도 0 으로 200.""")
    @GetMapping(value = "/internal/stats/orders", params = "storeId")
    public ApiResponse<StoreOrderStatsResponse> getStoreStats(@RequestParam Long storeId) {
        return ApiResponse.success("스토어 주문 통계를 조회했습니다.", orderStatsService.getStoreStats(storeId));
    }

    @Operation(summary = "결제일별 주문 통계 (일별 거래액 차트)", description = """
            오늘(KST) 포함 최근 days 일(기본 30, 1~90)의 결제일별 [{date, tradeAmt, orderCount}], 오래된 날부터 · 거래 없는 날도 0.
            tradeAmt = 그날 결제된 상품주문 금액 합계(배송비 · 포인트 차감 전, 결제 전 · 취소 · 반품 완료 제외) — totalTradeAmt 와 같은 기준.
            orderCount = 그날 결제된 결제 완료(PAID) 주문 수(전체 취소 제외). storeId 를 넣으면 그 스토어 상품주문만 — 주문 수는 그 스토어 매출 상품주문이 든 주문(판매자 일별 결제금액).
            에러: 400 INVALID_INPUT(days 범위 밖 · 숫자 아님)""")
    @GetMapping("/internal/stats/orders/daily")
    public ApiResponse<List<DailyOrderStatsResponse>> getDailyStats(@RequestParam(required = false) Long storeId,
                                                                     @RequestParam(defaultValue = "30") @Min(1) @Max(90) int days) {
        return ApiResponse.success("일별 주문 통계를 조회했습니다.", orderStatsService.getDailyStats(storeId, days));
    }

    @Operation(summary = "전체 주문 통계 (관리자 대시보드)", description = """
            storeId 없이 호출. totalTradeAmt = 결제된 상품주문 금액 합계(배송비 · 포인트 차감 전, 결제 전 · 취소 · 반품 완료 제외).
            orderCount = 결제 완료(PAID) 주문 수.""")
    @GetMapping(value = "/internal/stats/orders", params = "!storeId")
    public ApiResponse<PlatformOrderStatsResponse> getPlatformStats() {
        return ApiResponse.success("전체 주문 통계를 조회했습니다.", orderStatsService.getPlatformStats());
    }
}
