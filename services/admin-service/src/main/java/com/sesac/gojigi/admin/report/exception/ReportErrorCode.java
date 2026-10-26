package com.sesac.gojigi.admin.report.exception;

import com.sesac.gojigi.common.web.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 신고 · 조치 오류 (접두어 REPORT — docs/api/error-codes.md). 대상이 없으면 review · user 의 404 를 그대로 전달 */
@Getter
@RequiredArgsConstructor
public enum ReportErrorCode implements ErrorCode {
    REPORT_DUPLICATED(HttpStatus.CONFLICT, "이미 신고한 대상입니다."),
    REPORT_SELF(HttpStatus.BAD_REQUEST, "본인이나 본인이 쓴 리뷰는 신고할 수 없습니다."),
    REPORT_REASON_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "이 대상에는 선택할 수 없는 신고 사유입니다."),
    REPORT_NOT_OWN_STORE(HttpStatus.FORBIDDEN, "내 스토어 상품의 리뷰만 검토 요청할 수 있습니다."),
    REPORT_ACTION_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "이 대상에는 할 수 없는 조치입니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
