package com.sesac.gojigi.order.order.dto.response;

import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import java.util.Map;

/**
 * 판매자 주문 목록 (GET /api/seller/order-items) — 서버 페이징(최신 주문 먼저).
 * statusCounts: 내 스토어 상품주문의 상태별 개수(상태 탭 숫자, 검색 · 탭과 무관하게 전체) — 결제 전(ORDERED)은 판매자에게 보이지 않아 빠진다.
 */
public record SellerOrderItemListResponse(Map<OrderItemStatus, Long> statusCounts,
                                          PageResponse<SellerOrderItemResponse> items) {
}
