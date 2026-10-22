package com.sesac.gojigi.order.order.dto.event;

import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderItem;

/**
 * OrderItemShipped payload (docs/events.md) — 알림 Lambda(배송 시작 메일).
 * buyerId · ordererName · productName · optionName 은 10/21 추가분 (gotgan-config #34).
 */
public record OrderItemShippedPayload(Long orderItemId, String orderNo, Long buyerId, String ordererEmail,
                                      String ordererName, String productName, String optionName, String courier,
                                      String trackingNo) {

    public static OrderItemShippedPayload from(OrderItem item) {
        Order order = item.getOrder();
        return new OrderItemShippedPayload(item.getId(), order.getOrderNo(), order.getBuyerId(),
                order.getOrdererEmail(), order.getOrdererName(), item.getProductName(), item.getOptionName(),
                item.getCourier(), item.getTrackingNo());
    }
}
