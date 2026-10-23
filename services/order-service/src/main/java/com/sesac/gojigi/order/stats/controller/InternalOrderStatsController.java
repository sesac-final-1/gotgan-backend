package com.sesac.gojigi.order.stats.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.order.stats.dto.response.PlatformOrderStatsResponse;
import com.sesac.gojigi.order.stats.dto.response.StoreOrderStatsResponse;
import com.sesac.gojigi.order.stats.service.OrderStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 서비스 간 전용 (store 판매자 대시보드 · 휴면 검사, admin 관리자 대시보드) — 게이트웨이 라우트 없음, 클러스터 내부에서만 호출 */
@Tag(name = "주문 통계 (내부)", description = "store-service · admin-service 대시보드 조합이 호출 — 인가: 내부")
@RestController
@RequiredArgsConstructor
public class InternalOrderStatsController {

    private final OrderStatsService orderStatsService;

    @Operation(summary = "스토어 주문 통계 (판매자 대시보드 · 휴면 검사)", description = """
            toConfirmCount 발주확인 대기(PAYMENT_DONE) · toShipCount 송장 대기(PREPARING) · claimCount 진행 중 클레임(REQUESTED · APPROVED).
            inProgressCount = 세 값의 합 — 1 이상이면 미처리 주문이 있어 store 가 휴면 신청을 거절한다(STR-13).
            todaySalesAmt = 오늘(KST) 결제된 상품주문 금액 합계, 취소 · 반품 완료 제외. 없는 스토어도 0 으로 200.""")
    @GetMapping(value = "/internal/stats/orders", params = "storeId")
    public ApiResponse<StoreOrderStatsResponse> getStoreStats(@RequestParam Long storeId) {
        return ApiResponse.success("스토어 주문 통계를 조회했습니다.", orderStatsService.getStoreStats(storeId));
    }

    @Operation(summary = "전체 주문 통계 (관리자 대시보드)", description = """
            storeId 없이 호출. totalTradeAmt = 결제된 상품주문 금액 합계(배송비 · 포인트 차감 전, 결제 전 · 취소 · 반품 완료 제외).
            orderCount = 결제 완료(PAID) 주문 수.""")
    @GetMapping(value = "/internal/stats/orders", params = "!storeId")
    public ApiResponse<PlatformOrderStatsResponse> getPlatformStats() {
        return ApiResponse.success("전체 주문 통계를 조회했습니다.", orderStatsService.getPlatformStats());
    }
}
