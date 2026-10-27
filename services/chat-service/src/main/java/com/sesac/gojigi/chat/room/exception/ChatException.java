package com.sesac.gojigi.chat.room.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class ChatException extends BusinessException {

    public ChatException(ChatErrorCode errorCode) {
        super(errorCode);
    }
}
