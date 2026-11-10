package com.sesac.gojigi.admin.system.exception;

import com.sesac.gojigi.common.web.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 장애 시연 오류 (접두어 SYSTEM — docs/api/error-codes.md) */
@Getter
@RequiredArgsConstructor
public enum SystemErrorCode implements ErrorCode {
    SYSTEM_SERVICE_NOT_KILLABLE(HttpStatus.BAD_REQUEST, "내릴 수 없는 서비스입니다."),
    SYSTEM_CHAOS_DISABLED(HttpStatus.FORBIDDEN, "장애 시연 기능이 꺼져 있습니다."),
    SYSTEM_KILL_COOLDOWN(HttpStatus.CONFLICT, "복구 중이거나 방금 내린 서비스입니다. 잠시 뒤 다시 시도해 주세요."),
    SYSTEM_NO_READY_POD(HttpStatus.CONFLICT, "내릴 수 있는 파드가 없습니다."),
    SYSTEM_SCENARIO_NOT_READY(HttpStatus.CONFLICT, "모든 대상 서비스가 정상일 때만 실행할 수 있습니다."),
    SYSTEM_CLUSTER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "쿠버네티스 API 에 연결할 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
