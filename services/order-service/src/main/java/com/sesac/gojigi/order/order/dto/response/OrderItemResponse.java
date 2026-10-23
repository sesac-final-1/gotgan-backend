package com.sesac.gojigi.order.order.dto.response;

import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import java.time.LocalDateTime;

/**
 * 상품주문 1건 (ORD-08) — 주문할 때 남긴 스냅샷(상품명 · 옵션명 · 단가 · 스토어명)이라 상품이 바뀌어도 그대로 보인다.
 * usedPoint · earnedPoint 는 이 상품주문에 배분된 사용 · 적립 포인트 (취소 · 반품 시 환원 · 회수액).
 * status 는 결제 전 ORDERED(주문완료) — 결제 대기(PENDING) · 실패(FAILED) 주문의 상품주문은 ORDERED 로 남는다.
 */
public record OrderItemResponse(Long orderItemId, String itemNo, Long storeId, String storeName, Long productId,
                                Long productOptionId, String productName, String optionName, String thumbnailUrl,
                                long unitPrice, int quantity, long itemAmt, long usedPoint, long earnedPoint,
                                OrderItemStatus status, String courier, String trackingNo, LocalDateTime shippedAt,
                                LocalDateTime deliveredAt, LocalDateTime confirmedAt) {

    public static OrderItemResponse from(OrderItem i) {
        return new OrderItemResponse(i.getId(), i.getItemNo(), i.getStoreId(), i.getStoreName(), i.getProductId(),
                i.getProductOptionId(), i.getProductName(), i.getOptionName(), i.getThumbnailUrl(), i.getUnitPrice(),
                i.getQuantity(), i.getItemAmt(), i.getUsedPoint(), i.getEarnedPoint(), i.getStatus(), i.getCourier(),
                i.getTrackingNo(), i.getShippedAt(), i.getDeliveredAt(), i.getConfirmedAt());
    }
}
