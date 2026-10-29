package com.sesac.gojigi.settlement.settlement.dto.response;

import java.util.List;

/** 관리자 스토어 상세 — 한 스토어의 최근 n주 정산서(최신 주차 먼저) + 합계 (10/29). 항목 · 합계 모양은 주차별 목록과 같다 */
public record StoreRecentSettlementsResponse(Long storeId, int weeks, String fromWeek, SettlementListResponse.Totals totals,
                                             List<SettlementSummaryResponse> settlements) {
}
