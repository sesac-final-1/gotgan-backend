package com.sesac.gojigi.store.store.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class StoreException extends BusinessException {

    public StoreException(StoreErrorCode errorCode) {
        super(errorCode);
    }
}
