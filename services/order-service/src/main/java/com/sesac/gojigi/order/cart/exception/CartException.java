package com.sesac.gojigi.order.cart.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class CartException extends BusinessException {

    public CartException(CartErrorCode errorCode) {
        super(errorCode);
    }
}
