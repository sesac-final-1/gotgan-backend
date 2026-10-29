package com.sesac.gojigi.review.inquiry.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class InquiryException extends BusinessException {

    public InquiryException(InquiryErrorCode errorCode) {
        super(errorCode);
    }
}
