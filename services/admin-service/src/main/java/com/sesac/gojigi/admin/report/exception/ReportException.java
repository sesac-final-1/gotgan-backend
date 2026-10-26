package com.sesac.gojigi.admin.report.exception;

import com.sesac.gojigi.common.web.exception.BusinessException;

public class ReportException extends BusinessException {

    public ReportException(ReportErrorCode errorCode) {
        super(errorCode);
    }
}
