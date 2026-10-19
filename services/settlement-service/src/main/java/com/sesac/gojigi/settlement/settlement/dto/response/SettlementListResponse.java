package com.sesac.gojigi.settlement.settlement.dto.response;

import java.util.List;

/** 관리자 주차별 정산 목록 + 합계 행 (STL-05) — 데모 규모라 전체를 돌려주고 화면에서 검색 · 정렬 (컨벤션 4-7) */
public record SettlementListResponse(String week, Totals totals, List<SettlementSummaryResponse> settlements) {

    public record Totals(int count, long salesAmt, long commissionAmt, long paymentFeeAmt, long payoutAmt) {

        public static Totals of(List<SettlementSummaryResponse> rows) {
            return new Totals(rows.size(),
                    rows.stream().mapToLong(SettlementSummaryResponse::salesAmt).sum(),
                    rows.stream().mapToLong(SettlementSummaryResponse::commissionAmt).sum(),
                    rows.stream().mapToLong(SettlementSummaryResponse::paymentFeeAmt).sum(),
                    rows.stream().mapToLong(SettlementSummaryResponse::payoutAmt).sum());
        }
    }
}
