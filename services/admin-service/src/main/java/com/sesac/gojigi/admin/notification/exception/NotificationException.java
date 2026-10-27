package com.sesac.gojigi.admin.notification.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class NotificationException extends BusinessException {

    public NotificationException(NotificationErrorCode errorCode) {
        super(errorCode);
    }
}
