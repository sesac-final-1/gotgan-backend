package com.sesac.gojigi.user.auth.exception;

import com.sesac.gojigi.common.web.exception.ErrorCode;
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
    AUTH_ACCOUNT_NOT_LINKED(HttpStatus.UNAUTHORIZED, "회원 정보가 연결되지 않은 계정입니다."),
    /** 가입 — 이미 가입된 이메일 (탈퇴 회원 포함, uk_users_email) · Keycloak 에 같은 username 존재 */
    AUTH_EMAIL_DUPLICATE(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    /** 가입 — Keycloak 비밀번호 정책 위반 (8자 이상 · 영문 · 특수문자 · 이메일과 다름) */
    AUTH_PASSWORD_POLICY_VIOLATION(HttpStatus.BAD_REQUEST, "비밀번호는 8자 이상이며 영문과 특수문자를 포함하고, 이메일과 달라야 합니다."),
    /** 토큰 재발급 — Refresh 쿠키 없음 · 만료 · 로그아웃된 세션 (invalid_grant) */
    AUTH_SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다. 다시 로그인해 주세요."),
    /** 소셜 로그인 — 지원하지 않는 제공자 (google · kakao · naver 만) */
    AUTH_SOCIAL_PROVIDER_UNSUPPORTED(HttpStatus.BAD_REQUEST, "지원하지 않는 소셜 로그인입니다."),
    /** 소셜 로그인 — 제공자 로그인 취소 · state 불일치 · code 교환 실패 (결과 페이지 ?error= 로 전달) */
    AUTH_SOCIAL_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "소셜 로그인에 실패했습니다. 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
