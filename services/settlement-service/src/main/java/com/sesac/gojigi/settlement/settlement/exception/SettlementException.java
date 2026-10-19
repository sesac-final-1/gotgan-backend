package com.sesac.gojigi.settlement.settlement.exception;

import com.sesac.gojigi.settlement.common.exception.BusinessException;

public class SettlementException extends BusinessException {

    public SettlementException(SettlementErrorCode errorCode) {
        super(errorCode);
    }
}
