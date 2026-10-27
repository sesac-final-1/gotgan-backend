package com.sesac.gojigi.product.product.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class ProductException extends BusinessException {

    public ProductException(ProductErrorCode errorCode) {
        super(errorCode);
    }

    /** 화면에 구체적인 이유를 보여 줄 때 (옵션 · 이미지 규칙) */
    public ProductException(ProductErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
