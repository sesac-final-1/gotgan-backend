package com.sesac.gojigi.settlement.commission.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class CommissionException extends BusinessException {

    public CommissionException(CommissionErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
