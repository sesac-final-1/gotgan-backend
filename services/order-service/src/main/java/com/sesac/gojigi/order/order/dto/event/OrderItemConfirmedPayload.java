package com.sesac.gojigi.order.order.dto.event;

import com.sesac.gojigi.order.order.entity.OrderItem;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * OrderItemConfirmed payload (docs/events.md) — settlement(정산 적재) · review(작성 자격) · user(누적 구매액).
 * itemAmt = 상품주문 금액(포인트 차감 전) = 판매자 매출. topCategoryId(상위)가 판매수수료 기준.
 */
public record OrderItemConfirmedPayload(Long orderItemId, String orderNo, Long buyerId, Long storeId, Long productId,
                                        String productName, Long categoryId, Long topCategoryId, long itemAmt,
                                        OffsetDateTime confirmedAt) {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static OrderItemConfirmedPayload from(OrderItem item) {
        return new OrderItemConfirmedPayload(item.getId(), item.getOrder().getOrderNo(), item.getOrder().getBuyerId(),
                item.getStoreId(), item.getProductId(), item.getProductName(), item.getCategoryId(),
                item.getTopCategoryId(), item.getItemAmt(),
                item.getConfirmedAt() == null ? null : item.getConfirmedAt().atZone(KST).toOffsetDateTime());
    }
}
