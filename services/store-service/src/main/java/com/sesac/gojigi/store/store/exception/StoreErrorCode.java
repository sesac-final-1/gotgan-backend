package com.sesac.gojigi.store.store.exception;

import com.sesac.gojigi.common.web.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** store 도메인 오류 코드 (접두어 STORE — docs/api/error-codes.md) */
@Getter
@RequiredArgsConstructor
public enum StoreErrorCode implements ErrorCode {
    STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "스토어를 찾을 수 없습니다."),
    STORE_INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "폐점한 스토어는 상태를 바꿀 수 없습니다."),
    STORE_ORDERS_IN_PROGRESS(HttpStatus.CONFLICT, "진행 중인 주문이 있어 폐점할 수 없습니다."),
    STORE_SETTLEMENT_OUTSTANDING(HttpStatus.CONFLICT, "정산이 끝나지 않아 폐점할 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
