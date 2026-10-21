package com.sesac.gojigi.user.auth.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class AuthException extends BusinessException {

    public AuthException(AuthErrorCode errorCode) {
        super(errorCode);
    }
}
