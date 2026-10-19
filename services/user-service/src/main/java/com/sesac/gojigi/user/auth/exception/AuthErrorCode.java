package com.sesac.gojigi.user.auth.exception;

import com.sesac.gojigi.user.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {
    /** 이메일 · 비밀번호 불일치, Brute Force 임시 잠금 포함 (잠금 여부를 드러내지 않음 — 가이드라인 08장) */
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    /** 이용정지 회원 (Keycloak enabled=false · users.status SUSPENDED) */
    AUTH_ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "이용이 정지된 계정입니다."),
    /** Keycloak 계정은 있으나 userdb 회원과 연결되지 않음 (토큰에 user_id 없음 · users 행 없음) */
    AUTH_ACCOUNT_NOT_LINKED(HttpStatus.UNAUTHORIZED, "회원 정보가 연결되지 않은 계정입니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
