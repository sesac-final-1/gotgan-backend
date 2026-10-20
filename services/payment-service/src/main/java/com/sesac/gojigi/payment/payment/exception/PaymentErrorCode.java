package com.sesac.gojigi.payment.payment.exception;

import com.sesac.gojigi.common.web.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** payment 도메인 오류 코드 — 상황별 설명은 docs/api/payment.md 6절 */
@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "결제 정보를 찾을 수 없습니다."),
    PAYMENT_ORDER_CONFLICT(HttpStatus.CONFLICT, "같은 주문에 다른 금액으로 결제를 준비할 수 없습니다."),
    PAYMENT_NOT_COMPLETED(HttpStatus.CONFLICT, "결제가 완료되지 않았습니다."),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.CONFLICT, "결제 금액이 일치하지 않아 결제를 취소했습니다."),
    PAYMENT_INVALID_STATUS(HttpStatus.CONFLICT, "현재 결제 상태에서 처리할 수 없는 요청입니다."),
    PAYMENT_CANCEL_AMOUNT_EXCEEDED(HttpStatus.CONFLICT, "취소 금액이 남은 결제 금액보다 큽니다."),
    PAYMENT_WEBHOOK_INVALID_SIGNATURE(HttpStatus.BAD_REQUEST, "웹훅 서명이 올바르지 않습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
