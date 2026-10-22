package com.sesac.gojigi.user.address.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class AddressException extends BusinessException {

    public AddressException(AddressErrorCode errorCode) {
        super(errorCode);
    }
}
