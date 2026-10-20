package com.sesac.gojigi.settlement.settlement.dto.response;

/**
 * 스토어 미정산 현황 — 스토어 폐점 · 판매자 탈퇴 전 확인 (결정 3번 A안, 10/20 이경석). clear 가 true 일 때만 폐점 · 탈퇴 가능.
 * unsettled = 구매확정됐지만 아직 정산서에 묶이지 않은 상품주문(다음 집계 대상), scheduled · held = 지급 전 정산서.
 * 금액은 판매자가 받을 돈(지급액) 기준.
 */
public record SettlementOutstandingResponse(Long storeId, long unsettledItemCount, long unsettledPayoutAmt,
        long scheduledCount, long scheduledPayoutAmt, long heldCount, long heldPayoutAmt, boolean clear) {

    public static SettlementOutstandingResponse of(Long storeId, long unsettledItemCount, long unsettledPayoutAmt,
            long scheduledCount, long scheduledPayoutAmt, long heldCount, long heldPayoutAmt) {
        return new SettlementOutstandingResponse(storeId, unsettledItemCount, unsettledPayoutAmt, scheduledCount, scheduledPayoutAmt,
                heldCount, heldPayoutAmt, unsettledItemCount == 0 && scheduledCount == 0 && heldCount == 0);
    }
}
