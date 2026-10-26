package com.sesac.gojigi.order.stats.dto.response;

import java.time.LocalDate;

/**
 * 결제일(KST)별 주문 통계 (ADM-04 · DSH-03 일별 거래액 차트, 판매자 일별 결제금액) — 거래가 없는 날도 0 으로 채운다.
 * tradeAmt = 그날 결제된 상품주문 금액 합계(배송비 · 포인트 차감 전, 결제 전 · 취소 · 반품 완료 제외) — totalTradeAmt · todaySalesAmt 와 같은 기준.
 * orderCount = 그날 결제된 주문 중 결제 완료(PAID) 상태인 주문 수 — 전체 취소된 주문 제외. storeId 가 있으면 그 스토어의 매출 상품주문(취소 · 반품 완료 아님)이 든 주문.
 */
public record DailyOrderStatsResponse(LocalDate date, long tradeAmt, long orderCount) {
}
