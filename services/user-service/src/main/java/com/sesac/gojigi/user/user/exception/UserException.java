package com.sesac.gojigi.user.user.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class UserException extends BusinessException {

    public UserException(UserErrorCode errorCode) {
        super(errorCode);
    }
}
