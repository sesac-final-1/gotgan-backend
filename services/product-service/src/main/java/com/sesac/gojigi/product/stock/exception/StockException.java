package com.sesac.gojigi.product.stock.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class StockException extends BusinessException {

    public StockException(StockErrorCode errorCode) {
        super(errorCode);
    }

    /** 어느 옵션 때문인지 남길 때 (재고 부족) */
    public StockException(StockErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
