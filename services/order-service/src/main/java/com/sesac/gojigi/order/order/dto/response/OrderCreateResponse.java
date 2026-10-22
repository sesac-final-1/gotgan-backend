package com.sesac.gojigi.order.order.dto.response;

import com.sesac.gojigi.order.order.entity.OrderStatus;

/**
 * 주문 생성 결과 (ORD-04) — 브라우저는 portonePaymentId · payAmt · orderName 으로 포트원 결제창을 띄운다.
 * 전액 포인트(0원) 주문은 결제창 없이 바로 PAID — 이때 portonePaymentId 는 null.
 */
public record OrderCreateResponse(String orderNo, OrderStatus status, long payAmt, String portonePaymentId,
                                  String orderName) {
}
