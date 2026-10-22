package com.sesac.gojigi.order.common.logging;

import org.slf4j.MDC;

/**
 * 업무 키(orderNo · sagaId)를 로그 MDC 에 — 주문 한 건의 로그를 Grafana 에서 "orderNo":"..." 로 모아 보게 (요구사항 OBS-02 · 컨벤션 2-8).
 * 키 이름은 payment-service(PaymentLogContext)와 같아서 order → payment 로그가 주문번호 하나로 이어진다.
 * 요청 처리 중에는 OrderLogContextFilter 가 요청 끝에 지우고(예외 처리기 로그에도 남도록), 스케줄러는 건마다 직접 지운다.
 */
public final class OrderLogContext {

    public static final String ORDER_NO = "orderNo";
    public static final String SAGA_ID = "sagaId";

    private OrderLogContext() {
    }

    /** 값이 있는 것만 넣는다 */
    public static void put(String orderNo, String sagaId) {
        if (orderNo != null && !orderNo.isBlank()) {
            MDC.put(ORDER_NO, orderNo);
        }
        if (sagaId != null && !sagaId.isBlank()) {
            MDC.put(SAGA_ID, sagaId);
        }
    }

    public static void clear() {
        MDC.remove(ORDER_NO);
        MDC.remove(SAGA_ID);
    }
}
