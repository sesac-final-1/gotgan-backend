package com.sesac.gojigi.payment.payment.entity;

/** 결제 상태 (스펙 "결제 상태") — FAILED · CANCELLED 는 끝 상태 */
public enum PaymentStatus {
    READY,
    PAID,
    PARTIAL_CANCELLED,
    CANCELLED,
    FAILED;

    public boolean isFinal() {
        return this == FAILED || this == CANCELLED;
    }

    /** 포트원에 결제가 성립해 취소할 금액이 남은 상태 */
    public boolean isCancellable() {
        return this == PAID || this == PARTIAL_CANCELLED;
    }
}
