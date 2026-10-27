package com.sesac.gojigi.product.stock.exception;

import com.sesac.gojigi.common.web.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** stock 도메인 오류 코드 (접두어 STOCK — docs/api/error-codes.md · product.md 18절). order ProductClientAdapter 가 코드 이름으로 분기한다 */
@Getter
@RequiredArgsConstructor
public enum StockErrorCode implements ErrorCode {
    STOCK_INSUFFICIENT(HttpStatus.CONFLICT, "재고가 부족한 상품이 있습니다."),
    STOCK_RESERVATION_RELEASED(HttpStatus.CONFLICT, "이미 해제된 주문의 재고 예약입니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
