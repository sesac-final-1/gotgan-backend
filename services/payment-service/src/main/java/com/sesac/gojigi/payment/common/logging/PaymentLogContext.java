package com.sesac.gojigi.payment.common.logging;

import org.slf4j.MDC;

/**
 * 업무 키(orderNo · sagaId)를 로그 MDC 에 — 결제 한 건의 로그를 Grafana 에서 "orderNo":"..." 로 모아 보게 (요구사항 OBS-02 · 컨벤션 2-8).
 * Loki JSON 은 MDC 를 접두어 없이 전부 내보내므로 logback 설정은 바꾸지 않는다. 키 이름은 order-service 와 같게.
 * 요청 처리 중에는 PaymentLogContextFilter 가 요청 끝에 지우고(예외 처리기 로그에도 남도록), 스케줄러는 건마다 직접 지운다.
 */
public final class PaymentLogContext {

    public static final String ORDER_NO = "orderNo";
    public static final String SAGA_ID = "sagaId";

    private PaymentLogContext() {
    }

    /** 값이 있는 것만 넣는다 — 웹훅 · 상태 조회처럼 sagaId 가 없는 경우 */
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
