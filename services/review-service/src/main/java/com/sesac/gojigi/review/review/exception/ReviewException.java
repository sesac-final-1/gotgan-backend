package com.sesac.gojigi.review.review.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class ReviewException extends BusinessException {

    public ReviewException(ReviewErrorCode errorCode) {
        super(errorCode);
    }

    /** 화면에 구체적인 이유를 보여 줄 때 (사진 n번째 파일 · 개수) */
    public ReviewException(ReviewErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
