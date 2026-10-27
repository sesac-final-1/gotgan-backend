package com.sesac.gojigi.product.product.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class ProductException extends BusinessException {

    public ProductException(ProductErrorCode errorCode) {
        super(errorCode);
    }
}
