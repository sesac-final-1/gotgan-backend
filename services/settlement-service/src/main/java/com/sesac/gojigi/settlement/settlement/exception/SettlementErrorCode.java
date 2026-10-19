package com.sesac.gojigi.settlement.settlement.exception;

import com.sesac.gojigi.settlement.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** settlement 도메인 오류 코드 (접두어 SETTLE — docs/api/error-codes.md) */
@Getter
@RequiredArgsConstructor
public enum SettlementErrorCode implements ErrorCode {
    SETTLE_STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "정산 대상 스토어 정보가 없습니다."),
    SETTLE_NOT_FOUND(HttpStatus.NOT_FOUND, "정산서를 찾을 수 없습니다."),
    SETTLE_INVALID_STATUS(HttpStatus.CONFLICT, "현재 정산 상태에서 처리할 수 없는 요청입니다."),
    SETTLE_STORE_PAYOUT_HELD(HttpStatus.CONFLICT, "휴면 · 정지 스토어는 지급할 수 없습니다."),
    SETTLE_PAYOUT_ACCOUNT_MISSING(HttpStatus.CONFLICT, "정산 계좌 정보가 없어 지급할 수 없습니다."),
    SETTLE_HOLD_REASON_REQUIRED(HttpStatus.BAD_REQUEST, "보류 사유를 입력해 주세요."),
    SETTLE_REPORT_NOT_READY(HttpStatus.CONFLICT, "지급 완료된 정산만 명세서를 받을 수 있습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
