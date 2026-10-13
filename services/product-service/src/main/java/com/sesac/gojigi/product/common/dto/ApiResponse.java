package com.sesac.gojigi.product.common.dto;

import com.sesac.gojigi.product.common.exception.ErrorCode;

/**
 * 모든 응답의 봉투 — { code, message, data } (컨벤션 2-4). 성공 판별은 code == "SUCCESS" 하나.
 * 컨트롤러는 success() 만 쓰고, error() 는 GlobalExceptionHandler 가 만든다.
 */
public record ApiResponse<T>(String code, String message, T data) {

    public static final String SUCCESS_CODE = "SUCCESS";

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(SUCCESS_CODE, message, data);
    }

    public static ApiResponse<Void> success(String message) {
        return new ApiResponse<>(SUCCESS_CODE, message, null);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null);
    }
}
