package com.sesac.gojigi.product.product.exception;

import com.sesac.gojigi.common.web.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** product 도메인 오류 코드 (접두어 PRODUCT — docs/api/error-codes.md · product.md 18절). 나머지 코드는 쓰는 API 가 생길 때 추가 */
@Getter
@RequiredArgsConstructor
public enum ProductErrorCode implements ErrorCode {
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."),
    PRODUCT_BLOCK_REASON_REQUIRED(HttpStatus.BAD_REQUEST, "강제 판매중지 사유를 입력해 주세요.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
