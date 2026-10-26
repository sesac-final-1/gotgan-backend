package com.sesac.gojigi.admin.category.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class AdminCategoryException extends BusinessException {

    public AdminCategoryException(AdminCategoryErrorCode errorCode) {
        super(errorCode);
    }

    public AdminCategoryException(AdminCategoryErrorCode errorCode, Throwable cause) {
        super(errorCode, errorCode.getMessage(), cause);
    }
}
