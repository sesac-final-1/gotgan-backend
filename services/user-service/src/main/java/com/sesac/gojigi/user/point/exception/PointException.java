package com.sesac.gojigi.user.point.exception;

import com.sesac.gojigi.user.common.exception.BusinessException;

public class PointException extends BusinessException {

    public PointException(PointErrorCode errorCode) {
        super(errorCode);
    }
}
