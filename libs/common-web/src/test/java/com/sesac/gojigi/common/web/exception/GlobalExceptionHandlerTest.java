package com.sesac.gojigi.common.web.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.auth.CurrentUserArgumentResolver;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
            .setControllerAdvice(handler)
            .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
            .build();

    @Test
    @DisplayName("BusinessException 은 ErrorCode 의 상태와 코드로")
    void business() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("본문 @Valid 실패는 400 INVALID_INPUT, 메시지는 필드 메시지")
    void bodyValidation() throws Exception {
        mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("이름은 필수입니다."));
    }

    @Test
    @DisplayName("본문 파싱 실패 · 타입 불일치 · 필수 파라미터 누락은 400 INVALID_INPUT")
    void badRequestGroup() throws Exception {
        mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mockMvc.perform(get("/test/param").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mockMvc.perform(get("/test/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("지원하지 않는 메서드는 500 이 아니라 405 METHOD_NOT_ALLOWED")
    void methodNotAllowed() throws Exception {
        mockMvc.perform(delete("/test/param"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("예상 못 한 예외는 500 GLOBAL_ERROR")
    void unexpected() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("GLOBAL_ERROR"));
    }

    @Test
    @DisplayName("@CurrentUser — X-User-* 헤더를 AuthUser 로, X-User-Id 가 없으면 401")
    void currentUser() throws Exception {
        mockMvc.perform(get("/test/me")
                        .header(CurrentUserArgumentResolver.USER_ID_HEADER, "7")
                        .header(CurrentUserArgumentResolver.USER_ROLES_HEADER, "SELLER, BUYER")
                        .header(CurrentUserArgumentResolver.STORE_ID_HEADER, "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiResponse.SUCCESS_CODE))
                .andExpect(jsonPath("$.data.userId").value(7))
                .andExpect(jsonPath("$.data.roles[1]").value("BUYER"))
                .andExpect(jsonPath("$.data.storeId").value(3));
        mockMvc.perform(get("/test/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    /** standalone MockMvc 로는 만들기 어려운 예외 — 핸들러를 직접 호출 */
    @Test
    @DisplayName("없는 경로는 404 NOT_FOUND, 메서드 검증 실패는 400 INVALID_INPUT")
    void noResourceAndMethodValidation() {
        var notFound = handler.handleNoResource(new NoResourceFoundException(HttpMethod.GET, "/nope", "nope"));
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getBody().code()).isEqualTo("NOT_FOUND");

        var invalid = handler.handleMethodValidation(mock(HandlerMethodValidationException.class));
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody().code()).isEqualTo("INVALID_INPUT");
    }

    record NameRequest(@NotBlank(message = "이름은 필수입니다.") String name) {
    }

    @RestController
    static class TestController {

        @GetMapping("/test/business")
        ApiResponse<Void> business() {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        @PostMapping("/test/body")
        ApiResponse<Void> body(@Valid @RequestBody NameRequest request) {
            return ApiResponse.success("ok");
        }

        @GetMapping("/test/param")
        ApiResponse<Integer> param(@RequestParam("page") int page) {
            return ApiResponse.success("ok", page);
        }

        @GetMapping("/test/boom")
        ApiResponse<Void> boom() {
            throw new IllegalStateException("boom");
        }

        @GetMapping("/test/me")
        ApiResponse<AuthUser> me(@CurrentUser AuthUser user) {
            return ApiResponse.success("ok", user);
        }
    }
}
