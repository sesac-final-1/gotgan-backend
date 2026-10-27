package com.sesac.gojigi.product.product.exception;

import com.sesac.gojigi.common.web.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** product 도메인 오류 코드 (접두어 PRODUCT — docs/api/error-codes.md · product.md 18절). 나머지 코드는 쓰는 API PR 에서 추가 */
@Getter
@RequiredArgsConstructor
public enum ProductErrorCode implements ErrorCode {
    PRODUCT_CATEGORY_INVALID(HttpStatus.BAD_REQUEST, "사용 중인 하위 카테고리를 선택해 주세요."),
    PRODUCT_OPTION_INVALID(HttpStatus.BAD_REQUEST, "옵션 구성이 올바르지 않습니다."),
    PRODUCT_IMAGE_INVALID(HttpStatus.BAD_REQUEST, "상품 이미지가 올바르지 않습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
