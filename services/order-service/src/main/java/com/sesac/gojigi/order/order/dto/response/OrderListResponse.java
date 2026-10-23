package com.sesac.gojigi.order.order.dto.response;

import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import java.util.List;
import java.util.Map;

/**
 * 내 주문 목록 (ORD-08 GET /api/orders) — 수백 건 이하라 페이징 없이 전체, 최신 주문 먼저.
 * statusCounts: 상품주문 상태별 개수(모든 상태 키, 없으면 0) — 결제 대기 · 실패 주문의 상품주문은 ORDERED 로 세어진다.
 */
public record OrderListResponse(Map<OrderItemStatus, Long> statusCounts, List<OrderSummaryResponse> orders) {
}
