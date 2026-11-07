package com.sesac.gojigi.admin.system.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class SystemException extends BusinessException {

    public SystemException(SystemErrorCode errorCode) {
        super(errorCode);
    }

    public SystemException(SystemErrorCode errorCode, Throwable cause) {
        super(errorCode, errorCode.getMessage(), cause);
    }
}
