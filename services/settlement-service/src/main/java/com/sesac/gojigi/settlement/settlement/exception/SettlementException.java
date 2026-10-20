package com.sesac.gojigi.settlement.settlement.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class SettlementException extends BusinessException {

    public SettlementException(SettlementErrorCode errorCode) {
        super(errorCode);
    }
}
