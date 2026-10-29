package com.sesac.gojigi.store.store.client.dto;

/**
 * settlement GET /internal/settlements/outstanding?storeId= 중 폐점 판단에 쓰는 값만 (settlement.md 폐점 · 탈퇴 조건).
 * clear = 집계 전 · 지급 예정 · 보류가 모두 0건. 거절 문구의 금액은 세 금액의 합
 */
public record SettlementOutstandingResponse(Long storeId, long unsettledPayoutAmt, long scheduledPayoutAmt, long heldPayoutAmt,
                                            boolean clear) {

    public long outstandingAmt() {
        return unsettledPayoutAmt + scheduledPayoutAmt + heldPayoutAmt;
    }
}
