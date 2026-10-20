package com.sesac.gojigi.payment.payment.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class PaymentException extends BusinessException {

    public PaymentException(PaymentErrorCode errorCode) {
        super(errorCode);
    }
}
