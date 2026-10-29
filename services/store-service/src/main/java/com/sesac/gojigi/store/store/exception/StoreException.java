package com.sesac.gojigi.store.store.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class StoreException extends BusinessException {

    public StoreException(StoreErrorCode errorCode) {
        super(errorCode);
    }

    /** 화면에 구체적인 이유를 보여 줄 때 (진행 중 주문 n건 · 미정산 n원) */
    public StoreException(StoreErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
