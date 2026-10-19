package com.sesac.gojigi.settlement.settlement.exception;

import com.sesac.gojigi.settlement.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** settlement 도메인 오류 코드 (접두어 SETTLE — docs/api/error-codes.md) */
@Getter
@RequiredArgsConstructor
public enum SettlementErrorCode implements ErrorCode {
    SETTLE_STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "정산 대상 스토어 정보가 없습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
