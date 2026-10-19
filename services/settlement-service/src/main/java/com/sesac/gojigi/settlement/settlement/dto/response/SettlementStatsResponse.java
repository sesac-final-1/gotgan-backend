package com.sesac.gojigi.settlement.settlement.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 정산 통계 (STL-08, /internal/stats/settlements) — storeId 가 있으면 store 필드(판매자 대시보드), 없으면 platform 필드(관리자 대시보드).
 * 대시보드 조합(store · admin)이 부분 응답을 만들 때 그대로 쓴다.
 */
public record SettlementStatsResponse(StoreStats store, PlatformStats platform) {

    /** pending = 아직 집계 전(이번 주 구매확정분), nextPayout = 지급 전 정산서 중 가장 빠른 것, recentPaid = 최근 8주 지급 */
    public record StoreStats(Long storeId, long pendingItemCount, long pendingPayoutAmt, NextPayout nextPayout,
            List<Paid> recentPaid) {
    }

    public record NextPayout(Long settlementId, String week, long payoutAmt, LocalDate scheduledPayDate, String status) {
    }

    public record Paid(Long settlementId, String week, long payoutAmt, OffsetDateTime paidAt) {
    }

    /** scheduled · held = 지급 전 정산서 수 · 금액, commissionRevenueThisMonth = 이번 달(KST) 구매확정분 판매수수료 합 */
    public record PlatformStats(long scheduledCount, long scheduledAmt, long heldCount, long heldAmt,
            long paidThisMonthAmt, long commissionRevenueThisMonth) {
    }
}
