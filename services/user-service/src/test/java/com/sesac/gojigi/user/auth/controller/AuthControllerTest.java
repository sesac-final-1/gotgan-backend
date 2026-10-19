package com.sesac.gojigi.user.auth.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.user.auth.dto.response.LoginResponse;
import com.sesac.gojigi.user.auth.exception.AuthErrorCode;
import com.sesac.gojigi.user.auth.exception.AuthException;
import com.sesac.gojigi.user.auth.service.AuthService;
import com.sesac.gojigi.user.auth.service.LoginResult;
import com.sesac.gojigi.user.common.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(authService, new RefreshCookieProperties("refresh_token", "/api/auth", true)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("로그인 성공 — Access Token 은 body, Refresh Token 은 HttpOnly · Secure · SameSite=Strict 쿠키")
    void login_success_returnsTokenAndRefreshCookie() throws Exception {
        // given
        LoginResponse response = new LoginResponse("access", 1800,
                new LoginResponse.LoginUser(2L, "판매자1", List.of("BUYER", "SELLER"), 1L));
        given(authService.login(any())).willReturn(new LoginResult(response, "refresh", 1209600));

        // when · then
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"seller1@gotgan.com\",\"password\":\"Dem0!pass\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString("refresh_token=refresh"), containsString("Path=/api/auth"),
                        containsString("Max-Age=1209600"), containsString("Secure"),
                        containsString("HttpOnly"), containsString("SameSite=Strict"))))
                .andExpect(jsonPath("$.data.accessToken").value("access"))
                .andExpect(jsonPath("$.data.expiresIn").value(1800))
                .andExpect(jsonPath("$.data.user.userId").value(2))
                .andExpect(jsonPath("$.data.user.roles[1]").value("SELLER"))
                .andExpect(jsonPath("$.data.user.storeId").value(1));
    }

    @Test
    @DisplayName("이메일 형식이 틀리면 400 — 인증 서버를 부르지 않는다")
    void login_whenEmailInvalid_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"x\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("비밀번호가 틀리면 401 AUTH_INVALID_CREDENTIALS, 쿠키 없음")
    void login_whenInvalidCredentials_returns401() throws Exception {
        // given
        given(authService.login(any())).willThrow(new AuthException(AuthErrorCode.AUTH_INVALID_CREDENTIALS));

        // when · then
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"buyer1@gotgan.com\",\"password\":\"wrong!pass1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    }
}
