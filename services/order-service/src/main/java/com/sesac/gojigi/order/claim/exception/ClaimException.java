package com.sesac.gojigi.order.claim.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class ClaimException extends BusinessException {

    public ClaimException(ClaimErrorCode errorCode) {
        super(errorCode);
    }
}
