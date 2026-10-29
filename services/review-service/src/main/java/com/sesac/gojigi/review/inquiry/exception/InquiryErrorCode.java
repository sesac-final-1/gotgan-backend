package com.sesac.gojigi.review.inquiry.exception;

import com.sesac.gojigi.common.web.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 상품문의 오류 코드 (접두어 INQUIRY — docs/api/error-codes.md · review.md) */
@Getter
@RequiredArgsConstructor
public enum InquiryErrorCode implements ErrorCode {
    INQUIRY_NOT_FOUND(HttpStatus.NOT_FOUND, "상품 문의를 찾을 수 없습니다."),
    INQUIRY_PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "문의할 상품을 찾을 수 없습니다."),
    INQUIRY_ALREADY_ANSWERED(HttpStatus.CONFLICT, "판매자가 답변한 문의는 수정할 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
