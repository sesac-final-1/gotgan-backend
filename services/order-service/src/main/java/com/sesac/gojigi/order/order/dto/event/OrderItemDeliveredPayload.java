package com.sesac.gojigi.order.order.dto.event;

import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderItem;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * OrderItemDelivered payload — 알림 Lambda(배송 완료 메일). 10/26 추가 (docs/events.md 등록 필요 · gotgan-config).
 * autoConfirmAt = 자동 구매확정 예정 시각(배송완료 + app.order.auto-confirm-after) — 메일에 "○일 뒤 자동 구매확정" 안내용.
 */
public record OrderItemDeliveredPayload(Long orderItemId, String orderNo, Long buyerId, String ordererEmail,
                                        String ordererName, String productName, String optionName, String courier,
                                        String trackingNo, OffsetDateTime deliveredAt, OffsetDateTime autoConfirmAt) {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static OrderItemDeliveredPayload from(OrderItem item, Duration autoConfirmAfter) {
        Order order = item.getOrder();
        OffsetDateTime deliveredAt = item.getDeliveredAt().atZone(KST).toOffsetDateTime();
        return new OrderItemDeliveredPayload(item.getId(), order.getOrderNo(), order.getBuyerId(),
                order.getOrdererEmail(), order.getOrdererName(), item.getProductName(), item.getOptionName(),
                item.getCourier(), item.getTrackingNo(), deliveredAt, deliveredAt.plus(autoConfirmAfter));
    }
}
