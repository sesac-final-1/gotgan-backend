package com.sesac.gojigi.store.apply.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class ApplyException extends BusinessException {

    public ApplyException(ApplyErrorCode errorCode) {
        super(errorCode);
    }

    /** 화면에 구체적인 이유를 보여 줄 때 (서류 · 재신청 가능일) */
    public ApplyException(ApplyErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
