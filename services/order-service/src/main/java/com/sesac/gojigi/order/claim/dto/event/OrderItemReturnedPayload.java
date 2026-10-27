package com.sesac.gojigi.order.claim.dto.event;

import com.sesac.gojigi.order.claim.entity.OrderClaim;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderItem;

/**
 * OrderItemReturned payload (docs/events.md) — 알림 Lambda(반품 완료 메일). OrderItemCancelledPayload 와 필드가 같다.
 * restoredPoint = 돌려준 사용 포인트(order_item.used_point), revokedPoint = 회수한 적립 포인트 — 적립이 구매확정 때라 확정 전 반품은 0.
 */
public record OrderItemReturnedPayload(Long orderItemId, String orderNo, Long buyerId, String ordererEmail,
                                       String ordererName, String productName, String optionName, long refundAmt,
                                       long restoredPoint, long revokedPoint) {

    public static OrderItemReturnedPayload from(OrderClaim claim) {
        OrderItem item = claim.getOrderItem();
        Order order = item.getOrder();
        return new OrderItemReturnedPayload(item.getId(), order.getOrderNo(), order.getBuyerId(),
                order.getOrdererEmail(), order.getOrdererName(), item.getProductName(), item.getOptionName(),
                claim.getRefundAmt(), item.getUsedPoint(), item.revocablePoint());
    }
}
