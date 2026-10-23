package com.sesac.gojigi.order.order.dto.response;

import com.sesac.gojigi.order.order.client.dto.PaymentResponse;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderStatus;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 내 주문 상세 (ORD-08 GET /api/orders/{orderNo}) — 상품주문별 상태 · 배송지 스냅샷 · 금액 · 사용/적립 포인트 · 결제.
 * earnedPoint 는 상품주문 적립 포인트 합계. payment 는 payment-service 값이라 0원 주문 · 결제 준비 전 실패 · payment 장애면 null.
 */
public record OrderDetailResponse(String orderNo, OrderStatus status, LocalDateTime orderedAt, LocalDateTime paidAt,
                                  String receiverName, String receiverPhone, String zipcode, String address1,
                                  String address2, String deliveryMemo, long totalProductAmt, long totalShippingFee,
                                  long usedPoint, long payAmt, long earnedPoint, Payment payment,
                                  List<OrderItemResponse> items) {

    /** 결제 정보 — method: CARD 등 포트원 결제수단, status: READY · PAID · PARTIAL_CANCELLED · CANCELLED · FAILED */
    public record Payment(String method, String status, long cancelledAmount) {

        static Payment from(PaymentResponse p) {
            return p == null ? null : new Payment(p.method(), p.status(), p.cancelledAmount());
        }
    }

    public static OrderDetailResponse of(Order o, List<OrderItem> items, PaymentResponse payment) {
        return new OrderDetailResponse(o.getOrderNo(), o.getStatus(), o.getCreatedAt(), o.getPaidAt(),
                o.getReceiverName(), o.getReceiverPhone(), o.getZipcode(), o.getAddress1(), o.getAddress2(),
                o.getDeliveryMemo(), o.getTotalProductAmt(), o.getTotalShippingFee(), o.getUsedPoint(), o.getPayAmt(),
                items.stream().mapToLong(OrderItem::getEarnedPoint).sum(), Payment.from(payment),
                items.stream().map(OrderItemResponse::from).toList());
    }
}
