package com.sesac.gojigi.user.user.exception;

import com.sesac.gojigi.common.web.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."),
    USER_INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "현재 회원 상태에서 처리할 수 없는 요청입니다."),
    USER_STATUS_REASON_REQUIRED(HttpStatus.BAD_REQUEST, "이용정지 · 해제 사유를 입력해 주세요."),
    /** 탈퇴 — 판매자는 스토어를 폐점해야 탈퇴할 수 있다 */
    USER_SELLER_STORE_NOT_CLOSED(HttpStatus.CONFLICT, "스토어를 폐점한 뒤 탈퇴할 수 있습니다."),
    /** 비밀번호 변경 — 현재 비밀번호 불일치. 401 이면 프론트가 토큰 만료로 보고 재발급을 시도하므로 400 */
    USER_PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "현재 비밀번호가 일치하지 않습니다."),
    /** 비밀번호 변경 — 소셜 가입 회원은 Keycloak 비밀번호가 없다 */
    USER_PASSWORD_CHANGE_UNSUPPORTED(HttpStatus.CONFLICT, "소셜 로그인으로 가입한 회원은 비밀번호를 변경할 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
