package com.sesac.gojigi.order.order.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class OrderException extends BusinessException {

    public OrderException(OrderErrorCode errorCode) {
        super(errorCode);
    }
}
