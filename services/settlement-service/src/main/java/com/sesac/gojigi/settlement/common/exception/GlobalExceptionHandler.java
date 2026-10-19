package com.sesac.gojigi.settlement.common.exception;

import com.sesac.gojigi.settlement.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 예외 → ApiResponse 변환은 여기서만 (컨벤션 2-5). 서비스는 throw 만, 컨트롤러는 try-catch 없음.
 * HTTP 상태는 ErrorCode 의 status, 본문은 { code, message, data: null }.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        log.warn("business exception - code={}, message={}", code.getCode(), e.getMessage());
        return ResponseEntity.status(code.getStatus()).body(ApiResponse.error(code, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : CommonErrorCode.INVALID_INPUT.getMessage();
        return badRequest(message);
    }

    /** @RequestParam 제약(@Pattern 등) · List<@Valid ...> 본문 검증 실패 — Spring 6.1+ 기본 메서드 검증 (없으면 500 이 된다) */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodValidation(HandlerMethodValidationException e) {
        return badRequest(CommonErrorCode.INVALID_INPUT.getMessage());
    }

    /** 없는 경로 — Exception 핸들러로 가면 500 GLOBAL_ERROR 가 되어 장애로 오해한다 (10/16 트러블슈팅) */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(CommonErrorCode.NOT_FOUND.getStatus()).body(ApiResponse.error(CommonErrorCode.NOT_FOUND));
    }

    @ExceptionHandler({ MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception e) {
        return badRequest(CommonErrorCode.INVALID_INPUT.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("unexpected exception", e);
        return ResponseEntity.status(CommonErrorCode.GLOBAL_ERROR.getStatus())
                .body(ApiResponse.error(CommonErrorCode.GLOBAL_ERROR));
    }

    private ResponseEntity<ApiResponse<Void>> badRequest(String message) {
        return ResponseEntity.status(CommonErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.error(CommonErrorCode.INVALID_INPUT, message));
    }
}
