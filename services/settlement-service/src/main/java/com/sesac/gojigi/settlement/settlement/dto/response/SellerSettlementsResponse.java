package com.sesac.gojigi.settlement.settlement.dto.response;

import java.math.BigDecimal;
import java.util.List;

/** 판매자 정산 내역 (STL-07) — 요약(다음 지급 예정 · 최근 8주 지급완료 · 적용 수수료율) + 주차별 목록 */
public record SellerSettlementsResponse(Summary summary, List<SettlementSummaryResponse> settlements) {

    public record Summary(SettlementStatsResponse.NextPayout nextPayout, long paidRecent8WeeksAmt, long pendingPayoutAmt,
            List<AppliedRate> appliedRates) {
    }

    /** 최근 구매확정분에 적용된 상위 카테고리별 판매수수료율 */
    public record AppliedRate(Long categoryId, BigDecimal rate) {
    }
}
