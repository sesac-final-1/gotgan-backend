package com.sesac.gojigi.store.apply.exception;

import com.sesac.gojigi.common.web.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** apply 도메인 오류 코드 (접두어 APPLY — docs/api/error-codes.md · store.md 10절) */
@Getter
@RequiredArgsConstructor
public enum ApplyErrorCode implements ErrorCode {
    APPLY_STORE_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 스토어가 있는 회원은 입점 신청을 할 수 없습니다."),
    APPLY_IN_PROGRESS(HttpStatus.CONFLICT, "심사 중인 입점 신청이 있습니다."),
    APPLY_REAPPLY_RESTRICTED(HttpStatus.CONFLICT, "반려 후 30일이 지나야 다시 신청할 수 있습니다."),
    APPLY_SLUG_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 스토어 주소입니다."),
    APPLY_STORE_NAME_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 스토어명입니다."),
    APPLY_DOCUMENT_INVALID(HttpStatus.BAD_REQUEST, "입점 서류가 올바르지 않습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
