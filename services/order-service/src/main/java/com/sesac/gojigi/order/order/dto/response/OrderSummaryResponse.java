package com.sesac.gojigi.order.order.dto.response;

import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderStatus;
import java.time.LocalDateTime;
import java.util.List;

/** 내 주문 목록의 주문 1건 (ORD-08) — 주문 카드 하나 = 주문 + 상품주문들 */
public record OrderSummaryResponse(String orderNo, OrderStatus status, long payAmt, LocalDateTime orderedAt,
                                   List<OrderItemResponse> items) {

    public static OrderSummaryResponse of(Order order, List<OrderItem> items) {
        return new OrderSummaryResponse(order.getOrderNo(), order.getStatus(), order.getPayAmt(), order.getCreatedAt(),
                items.stream().map(OrderItemResponse::from).toList());
    }
}
