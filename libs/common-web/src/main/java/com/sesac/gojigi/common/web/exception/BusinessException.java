package com.sesac.gojigi.common.web.exception;

/** 모든 비즈니스 예외의 부모. 도메인 예외는 이 클래스를 상속한다 ({Domain}Exception + {Domain}ErrorCode). */
public class BusinessException extends RuntimeException {

    private final transient ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
