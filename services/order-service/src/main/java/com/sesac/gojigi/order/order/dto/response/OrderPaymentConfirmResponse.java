package com.sesac.gojigi.order.order.dto.response;

import com.sesac.gojigi.order.order.entity.OrderStatus;

/** 결제 확인 결과 (ORD-06) — 성공이면 항상 PAID. 같은 주문을 다시 확인해도 같은 응답 */
public record OrderPaymentConfirmResponse(String orderNo, OrderStatus status, long payAmt) {
}
