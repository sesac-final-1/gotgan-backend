package com.sesac.gojigi.admin.notification.exception;

import com.sesac.gojigi.common.web.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 알림 센터 오류 (접두어 NOTIFICATION — docs/api/error-codes.md) */
@Getter
@RequiredArgsConstructor
public enum NotificationErrorCode implements ErrorCode {
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),
    NOTIFICATION_RECIPIENT_REQUIRED(HttpStatus.BAD_REQUEST, "알림 받을 대상을 하나 이상 지정해 주세요.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
