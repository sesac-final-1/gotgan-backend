package com.sesac.gojigi.payment.payment.exception;

import com.sesac.gojigi.payment.common.exception.BusinessException;

public class PaymentException extends BusinessException {

    public PaymentException(PaymentErrorCode errorCode) {
        super(errorCode);
    }
}
