package com.sesac.gojigi.order.stats.dto.response;

/**
 * 전체 주문 통계 (ORD-10) — admin 관리자 대시보드(ADM-02) 거래액 · 주문 수.
 * totalTradeAmt = 결제된 상품주문 금액 합계(배송비 · 포인트 차감 전), 결제 전 · 취소 · 반품 완료 제외.
 * orderCount = 결제 완료(PAID) 주문 수 — 결제 대기 · 실패 · 전체 취소 주문 제외.
 */
public record PlatformOrderStatsResponse(long totalTradeAmt, long orderCount) {
}
