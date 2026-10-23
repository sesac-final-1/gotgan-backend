package com.sesac.gojigi.order.stats.dto.response;

/**
 * 스토어 주문 통계 (ORD-10) — store 판매자 대시보드(STR-07) 할 일 · 오늘 매출, store 휴면 신청 검사(STR-13).
 * toConfirmCount 발주확인 대기(PAYMENT_DONE) · toShipCount 송장 대기(PREPARING) · claimCount 진행 중 클레임(REQUESTED · APPROVED).
 * inProgressCount = 세 값의 합 — 1 이상이면 미처리 주문이 있어 휴면 신청 거절.
 * todaySalesAmt = 오늘(KST) 결제된 상품주문 금액 합계, 취소 · 반품 완료 제외.
 */
public record StoreOrderStatsResponse(Long storeId, long toConfirmCount, long toShipCount, long claimCount,
                                      long inProgressCount, long todaySalesAmt) {

    public static StoreOrderStatsResponse of(Long storeId, long toConfirmCount, long toShipCount, long claimCount,
                                             long todaySalesAmt) {
        return new StoreOrderStatsResponse(storeId, toConfirmCount, toShipCount, claimCount,
                toConfirmCount + toShipCount + claimCount, todaySalesAmt);
    }
}
