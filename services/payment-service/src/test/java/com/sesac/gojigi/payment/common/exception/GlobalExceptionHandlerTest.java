package com.sesac.gojigi.payment.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/** 컨트롤러에 아직 메서드 검증 제약이 없어 MockMvc 로는 못 만드는 예외 — 핸들러를 직접 호출 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("메서드 검증 실패(@RequestParam 제약 · List<@Valid>)는 500 이 아니라 400 INVALID_INPUT")
    void methodValidation_badRequest() {
        var response = handler.handleMethodValidation(mock(HandlerMethodValidationException.class));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(CommonErrorCode.INVALID_INPUT.getCode());
    }
}
