package com.sesac.gojigi.common.web.exception;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
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
 * Spring 이 던지는 4xx 성격 예외(400 묶음 · 404 · 405)를 빠짐없이 잡아 500 GLOBAL_ERROR 로 새지 않게 한다
 * (트러블슈팅 10/16 · 10/19, 405 는 10/20 박지연 제안).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        log.warn("business exception - code={}, message={}", code.getCode(), e.getMessage());
        return ResponseEntity.status(code.getStatus()).body(ApiResponse.error(code, e.getMessage()));
    }

    /** 본문 @Valid 실패 — 첫 필드 메시지를 그대로 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : CommonErrorCode.INVALID_INPUT.getMessage();
        return badRequest(message);
    }

    /** @RequestParam 제약(@Pattern 등) · List<@Valid ...> 본문 검증 실패 — Spring 6.1+ 기본 메서드 검증 */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodValidation(HandlerMethodValidationException e) {
        return badRequest(CommonErrorCode.INVALID_INPUT.getMessage());
    }

    /** 타입 불일치 · 필수 파라미터 누락 · 본문 파싱 실패 */
    @ExceptionHandler({ MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception e) {
        return badRequest(CommonErrorCode.INVALID_INPUT.getMessage());
    }

    /** 없는 경로 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        return of(CommonErrorCode.NOT_FOUND);
    }

    /** 경로는 있는데 메서드가 틀림 (예: GET 만 있는 경로에 DELETE) */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return of(CommonErrorCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("unexpected exception", e);
        return of(CommonErrorCode.GLOBAL_ERROR);
    }

    private static ResponseEntity<ApiResponse<Void>> badRequest(String message) {
        return ResponseEntity.status(CommonErrorCode.INVALID_INPUT.getStatus())
                .body(ApiResponse.error(CommonErrorCode.INVALID_INPUT, message));
    }

    private static ResponseEntity<ApiResponse<Void>> of(CommonErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(ApiResponse.error(code));
    }
}
