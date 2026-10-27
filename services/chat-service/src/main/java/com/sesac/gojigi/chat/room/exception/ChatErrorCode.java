package com.sesac.gojigi.chat.room.exception;

import com.sesac.gojigi.common.web.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 채팅 오류 (접두어 CHAT — docs/api/error-codes.md · chat.md 9절) */
@Getter
@RequiredArgsConstructor
public enum ChatErrorCode implements ErrorCode {
    CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다."),
    CHAT_NOT_ALLOWED(HttpStatus.FORBIDDEN, "이 채팅방을 열거나 보낼 수 없습니다."),
    CHAT_SELF(HttpStatus.BAD_REQUEST, "내 스토어에는 문의할 수 없습니다."),
    CHAT_INVALID_TYPE(HttpStatus.BAD_REQUEST, "상담방만 상태를 바꿀 수 있습니다."),
    CHAT_TOO_FAST(HttpStatus.TOO_MANY_REQUESTS, "메시지를 너무 빠르게 보내고 있습니다."),
    CHAT_STORE_INACTIVE(HttpStatus.CONFLICT, "운영 중이 아닌 스토어에는 새 문의를 열 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
