package com.sesac.gojigi.review.review.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class ReviewException extends BusinessException {

    public ReviewException(ReviewErrorCode errorCode) {
        super(errorCode);
    }
}
