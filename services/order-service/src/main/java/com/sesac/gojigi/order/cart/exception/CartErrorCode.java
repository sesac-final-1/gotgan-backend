package com.sesac.gojigi.order.cart.exception;

import com.sesac.gojigi.common.web.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** cart 도메인 에러 코드 (컨벤션 2-5 · docs/api/error-codes.md) */
@Getter
@RequiredArgsConstructor
public enum CartErrorCode implements ErrorCode {
    /** 없는 옵션 · 판매중지 · 차단 · 삭제된 상품 */
    CART_PRODUCT_NOT_PURCHASABLE(HttpStatus.CONFLICT, "장바구니에 담을 수 없는 상품입니다."),
    /** 장바구니에 없는 옵션 (수량 · 선택 변경) */
    CART_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "장바구니에 없는 상품입니다."),
    /** 담긴 수량 + 새로 담는 수량이 옵션당 최대(999)를 넘음 */
    CART_QUANTITY_EXCEEDED(HttpStatus.CONFLICT, "한 옵션은 999개까지 담을 수 있어요.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
