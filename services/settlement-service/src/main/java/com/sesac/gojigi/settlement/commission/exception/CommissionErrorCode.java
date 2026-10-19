package com.sesac.gojigi.settlement.commission.exception;

import com.sesac.gojigi.settlement.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** commission 도메인 오류 코드 (접두어 COMMISSION — docs/api/error-codes.md) */
@Getter
@RequiredArgsConstructor
public enum CommissionErrorCode implements ErrorCode {
    COMMISSION_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 카테고리의 판매수수료율이 등록되지 않았습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
