package com.sesac.gojigi.payment.payment.client.dto;

import java.time.OffsetDateTime;

/**
 * 포트원 V2 결제 조회 응답 중 쓰는 필드만 (나머지는 무시). status 별로 필드가 다르다 — PAID 일 때 paidAt · pgTxId 가 있다.
 * status: READY / PAY_PENDING / VIRTUAL_ACCOUNT_ISSUED / PAID / FAILED / PARTIAL_CANCELLED / CANCELLED
 */
public record PortOnePayment(String status, String id, Amount amount, Method method, String pgTxId, OffsetDateTime paidAt) {

    public record Amount(long total, long paid, long cancelled) {
    }

    /** type 예: PaymentMethodCard · PaymentMethodEasyPay */
    public record Method(String type) {
    }

    public boolean isPaid() {
        return "PAID".equals(status);
    }

    public long totalAmount() {
        return amount == null ? 0 : amount.total();
    }

    /** 포트원 기준 남은 취소 가능 금액 — 취소 요청의 currentCancellableAmount */
    public long cancellableAmount() {
        return amount == null ? 0 : amount.paid() - amount.cancelled();
    }

    /** payment.method 컬럼 값 — PaymentMethodEasyPay → EASY_PAY */
    public String methodCode() {
        if (method == null || method.type() == null) {
            return null;
        }
        String name = method.type().replaceFirst("^PaymentMethod", "");
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
    }
}
