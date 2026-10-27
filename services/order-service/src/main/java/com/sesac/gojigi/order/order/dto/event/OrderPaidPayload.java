package com.sesac.gojigi.order.order.dto.event;

import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderItem;
import java.util.List;

/**
 * OrderPaid payload (gotgan-config docs/events.md) — 알림 Lambda(주문 확인 메일) · 알림 센터. items[].earnedPoint 는 적립 예정 안내용
 * (실제 적립은 구매확정 때 — OrderItemConfirmed).
 * ordererName 부터는 10/21 추가분 (gotgan-config #34). ordererName 은 실명 — 메일 본문 전용.
 */
public record OrderPaidPayload(String orderNo, Long buyerId, String ordererEmail, String ordererName, long payAmt,
                               long usedPoint, List<Item> items) {

    /** optionName 은 옵션 없는 상품이면 null */
    public record Item(Long orderItemId, long earnedPoint, String productName, String optionName, int quantity,
                       long itemAmt) {

        static Item from(OrderItem item) {
            return new Item(item.getId(), item.getEarnedPoint(), item.getProductName(), item.getOptionName(),
                    item.getQuantity(), item.getItemAmt());
        }
    }

    public static OrderPaidPayload of(Order order, List<OrderItem> items) {
        return new OrderPaidPayload(order.getOrderNo(), order.getBuyerId(), order.getOrdererEmail(),
                order.getOrdererName(), order.getPayAmt(), order.getUsedPoint(),
                items.stream().map(Item::from).toList());
    }
}
