package com.sesac.gojigi.user.point.exception;

import com.sesac.gojigi.common.web.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PointErrorCode implements ErrorCode {
    POINT_INSUFFICIENT(HttpStatus.CONFLICT, "포인트가 부족합니다."),
    POINT_INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "포인트 금액이 올바르지 않습니다."),
    /** 같은 sagaId 로 다른 회원 · 금액의 사용 요청 — order 쪽 버그 (멱등 키 재사용) */
    POINT_USE_CONFLICT(HttpStatus.CONFLICT, "같은 sagaId 로 다른 포인트 사용 요청이 이미 처리되었습니다."),
    /** 사용 취소(Saga 보상)가 먼저 처리된 sagaId 의 사용 — order 가 타임아웃으로 포기한 뒤 늦게 도착한 요청 */
    POINT_USE_RELEASED(HttpStatus.CONFLICT, "이미 취소된 주문의 포인트 사용 요청입니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
