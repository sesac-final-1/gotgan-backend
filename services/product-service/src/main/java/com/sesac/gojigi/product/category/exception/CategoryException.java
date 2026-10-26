package com.sesac.gojigi.product.category.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class CategoryException extends BusinessException {

    public CategoryException(CategoryErrorCode errorCode) {
        super(errorCode);
    }
}
