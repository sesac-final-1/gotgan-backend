package com.sesac.gojigi.user.auth.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.user.auth.component.SocialLoginUrls;
import com.sesac.gojigi.user.auth.dto.response.LoginResponse;
import com.sesac.gojigi.user.auth.dto.result.LoginResult;
import com.sesac.gojigi.user.auth.dto.result.SignupResult;
import com.sesac.gojigi.user.auth.dto.result.SocialLoginResult;
import com.sesac.gojigi.user.auth.dto.result.SocialLoginStart;
import com.sesac.gojigi.user.auth.exception.AuthErrorCode;
import com.sesac.gojigi.user.auth.exception.AuthException;
import com.sesac.gojigi.user.auth.service.AuthService;
import jakarta.servlet.http.Cookie;
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

    private static final SocialLoginUrls URLS = new SocialLoginUrls("https://api.example", "gotgan", "gotgan-backend",
            "https://front.example/api/auth/social/callback", "https://front.example/auth/callback");

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(authService, URLS, "social_login", "refresh_token", "/api/auth", true))
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

    private static final String SIGNUP_BODY =
            "{\"email\":\"new1@gotgan.com\",\"password\":\"Newbie!pass1\",\"name\":\"새회원\",\"phone\":\"010-1234-5678\"}";

    @Test
    @DisplayName("회원가입 + 자동 로그인 — 201, data.login 은 로그인 응답과 같고 Refresh Token 은 쿠키")
    void signup_withAutoLogin_returns201WithCookie() throws Exception {
        // given
        LoginResponse login = new LoginResponse("access", 1800,
                new LoginResponse.LoginUser(7L, "새회원", List.of("BUYER"), null));
        given(authService.signup(any())).willReturn(SignupResult.loggedIn(7L, new LoginResult(login, "refresh", 1209600)));

        // when · then
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(SIGNUP_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString("refresh_token=refresh"), containsString("HttpOnly"), containsString("SameSite=Strict"))))
                .andExpect(jsonPath("$.message").value("회원가입이 완료되었습니다."))
                .andExpect(jsonPath("$.data.userId").value(7))
                .andExpect(jsonPath("$.data.login.accessToken").value("access"))
                .andExpect(jsonPath("$.data.login.user.roles[0]").value("BUYER"));
    }

    @Test
    @DisplayName("회원가입은 됐지만 자동 로그인 실패 — 201, data.login 은 null, 쿠키 없음")
    void signup_withoutAutoLogin_returns201WithoutCookie() throws Exception {
        // given
        given(authService.signup(any())).willReturn(SignupResult.withoutLogin(7L));

        // when · then
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(SIGNUP_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$.message").value("회원가입이 완료되었습니다. 로그인해 주세요."))
                .andExpect(jsonPath("$.data.userId").value(7))
                .andExpect(jsonPath("$.data.login").doesNotExist());
    }

    @Test
    @DisplayName("회원가입 입력 오류는 400 INVALID_INPUT — 가입 서비스를 부르지 않는다")
    void signup_invalidInput_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new1@gotgan.com\",\"password\":\"abcdefgh1\",\"name\":\"새회원\",\"phone\":\"010-1234-5678\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("비밀번호는 영문과 특수문자를 포함해야 합니다."));
        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("이미 가입된 이메일로 가입하면 409 AUTH_EMAIL_DUPLICATE")
    void signup_duplicateEmail_returns409() throws Exception {
        // given
        given(authService.signup(any())).willThrow(new AuthException(AuthErrorCode.AUTH_EMAIL_DUPLICATE));

        // when · then
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(SIGNUP_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_EMAIL_DUPLICATE"));
    }

    @Test
    @DisplayName("이메일 중복 확인 — 소문자로 바꿔 확인하고 사용 가능 여부를 돌려준다")
    void emailAvailability_returnsAvailability() throws Exception {
        // given
        given(authService.isEmailAvailable("new1@gotgan.com")).willReturn(true);
        given(authService.isEmailAvailable("seller1@gotgan.com")).willReturn(false);

        // when · then
        mockMvc.perform(get("/api/auth/email-availability").param("email", "New1@Gotgan.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("사용할 수 있는 이메일입니다."))
                .andExpect(jsonPath("$.data.email").value("new1@gotgan.com"))
                .andExpect(jsonPath("$.data.available").value(true));
        mockMvc.perform(get("/api/auth/email-availability").param("email", "seller1@gotgan.com"))
                .andExpect(jsonPath("$.message").value("이미 가입된 이메일입니다."))
                .andExpect(jsonPath("$.data.available").value(false));
    }

    @Test
    @DisplayName("이메일 중복 확인 — 형식이 틀리면 400 INVALID_INPUT")
    void emailAvailability_invalidEmail_returns400() throws Exception {
        mockMvc.perform(get("/api/auth/email-availability").param("email", "not-an-email"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("로그아웃 — Refresh 쿠키 값으로 세션을 끝내고, 같은 이름 · 경로로 Max-Age=0 쿠키를 내려 지운다")
    void logout_withCookie_endsSessionAndClearsCookie() throws Exception {
        mockMvc.perform(post("/api/auth/logout").cookie(new Cookie("refresh_token", "rt-1")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString("refresh_token=;"), containsString("Max-Age=0"), containsString("Path=/api/auth"),
                        containsString("HttpOnly"), containsString("SameSite=Strict"))))
                .andExpect(jsonPath("$.message").value("로그아웃되었습니다."));
        verify(authService).logout("rt-1");
    }

    @Test
    @DisplayName("로그아웃 — 쿠키가 없어도 200, 쿠키 삭제 응답")
    void logout_withoutCookie_returns200() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
        verify(authService).logout(null);
    }

    @Test
    @DisplayName("재발급 — Refresh 쿠키로 새 Access Token(body) · 새 Refresh 쿠키")
    void refresh_withCookie_returnsTokenAndRotatesCookie() throws Exception {
        // given
        LoginResponse response = new LoginResponse("access-2", 1800, new LoginResponse.LoginUser(7L, "카카오회원", List.of("BUYER"), null));
        given(authService.refresh("rt-1")).willReturn(new LoginResult(response, "rt-2", 1209600));

        // when · then
        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", "rt-1")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(containsString("refresh_token=rt-2"), containsString("HttpOnly"))))
                .andExpect(jsonPath("$.data.accessToken").value("access-2"))
                .andExpect(jsonPath("$.data.user.userId").value(7));
    }

    @Test
    @DisplayName("재발급 — 쿠키 없음 · 만료는 401 AUTH_SESSION_EXPIRED")
    void refresh_expired_returns401() throws Exception {
        given(authService.refresh(null)).willThrow(new AuthException(AuthErrorCode.AUTH_SESSION_EXPIRED));

        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_EXPIRED"));
    }

    @Test
    @DisplayName("소셜 시작 — Keycloak 로 302, state 쿠키는 HttpOnly · SameSite=Lax · Path=/api/auth/social · 10분")
    void startSocialLogin_redirectsWithStateCookie() throws Exception {
        given(authService.startSocialLogin("kakao")).willReturn(new SocialLoginStart("https://api.example/realms/gotgan/auth?x", "kakao.s.v"));

        mockMvc.perform(get("/api/auth/social/kakao"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://api.example/realms/gotgan/auth?x"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(containsString("social_login=kakao.s.v"),
                        containsString("Path=/api/auth/social"), containsString("Max-Age=600"), containsString("HttpOnly"),
                        containsString("SameSite=Lax"))));
    }

    @Test
    @DisplayName("소셜 콜백 성공 — state 쿠키 삭제 · Refresh 쿠키 발급 · 결과 페이지로 302")
    void completeSocialLogin_success_setsRefreshCookieAndRedirects() throws Exception {
        LoginResponse response = new LoginResponse("access", 1800, new LoginResponse.LoginUser(7L, "카카오회원", List.of("BUYER"), null));
        given(authService.completeSocialLogin("kakao.s.v", "c-1", "s", null))
                .willReturn(SocialLoginResult.success(new LoginResult(response, "rt-1", 1209600)));

        mockMvc.perform(get("/api/auth/social/callback").param("code", "c-1").param("state", "s")
                        .cookie(new Cookie("social_login", "kakao.s.v")))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://front.example/auth/callback"))
                .andExpect(header().stringValues(HttpHeaders.SET_COOKIE, hasItem(allOf(startsWith("social_login=;"), containsString("Max-Age=0")))))
                .andExpect(header().stringValues(HttpHeaders.SET_COOKIE, hasItem(allOf(startsWith("refresh_token=rt-1"),
                        containsString("Path=/api/auth"), containsString("SameSite=Strict")))));
    }

    @Test
    @DisplayName("소셜 콜백 실패 — Refresh 쿠키 없이 결과 페이지?error=코드 로 302")
    void completeSocialLogin_failure_redirectsWithError() throws Exception {
        given(authService.completeSocialLogin(null, null, null, "access_denied"))
                .willReturn(SocialLoginResult.failure("AUTH_SOCIAL_LOGIN_FAILED"));

        mockMvc.perform(get("/api/auth/social/callback").param("error", "access_denied"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://front.example/auth/callback?error=AUTH_SOCIAL_LOGIN_FAILED"))
                .andExpect(header().stringValues(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.everyItem(startsWith("social_login=;"))));
    }
}
