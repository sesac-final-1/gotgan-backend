package com.sesac.gojigi.order.order.dto.response;

import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import java.time.LocalDateTime;

/**
 * 판매자 주문 관리의 상품주문 1건 (ORD-12 · DLV) — 주문 때 남긴 스냅샷이라 다른 서비스를 부르지 않는다.
 * buyerName 은 공개용 이름(닉네임 또는 가운데 마스킹 실명), 배송에 필요한 수령인 · 연락처 · 주소는 그대로.
 */
public record SellerOrderItemResponse(Long orderItemId, String itemNo, String orderNo, LocalDateTime orderedAt,
                                      LocalDateTime paidAt, OrderItemStatus status, Long productId, String productName,
                                      String optionName, String thumbnailUrl, long unitPrice, int quantity, long itemAmt,
                                      String buyerName, String receiverName, String receiverPhone, String zipcode,
                                      String address1, String address2, String deliveryMemo, String courier,
                                      String trackingNo, LocalDateTime shippedAt, LocalDateTime deliveredAt,
                                      LocalDateTime confirmedAt) {

    public static SellerOrderItemResponse from(OrderItem i) {
        Order o = i.getOrder();
        return new SellerOrderItemResponse(i.getId(), i.getItemNo(), o.getOrderNo(), o.getCreatedAt(), o.getPaidAt(),
                i.getStatus(), i.getProductId(), i.getProductName(), i.getOptionName(), i.getThumbnailUrl(),
                i.getUnitPrice(), i.getQuantity(), i.getItemAmt(), o.getOrdererDisplayName(), o.getReceiverName(),
                o.getReceiverPhone(), o.getZipcode(), o.getAddress1(), o.getAddress2(), o.getDeliveryMemo(),
                i.getCourier(), i.getTrackingNo(), i.getShippedAt(), i.getDeliveredAt(), i.getConfirmedAt());
    }
}
