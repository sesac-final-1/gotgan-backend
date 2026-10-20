package com.sesac.gojigi.user.auth.controller;

import com.sesac.gojigi.user.auth.dto.request.EmailAvailabilityRequest;
import com.sesac.gojigi.user.auth.dto.request.LoginRequest;
import com.sesac.gojigi.user.auth.dto.request.SignupRequest;
import com.sesac.gojigi.user.auth.dto.response.EmailAvailabilityResponse;
import com.sesac.gojigi.user.auth.dto.response.LoginResponse;
import com.sesac.gojigi.user.auth.dto.response.SignupResponse;
import com.sesac.gojigi.user.auth.service.AuthService;
import com.sesac.gojigi.user.auth.service.LoginResult;
import com.sesac.gojigi.user.auth.service.SignupResult;
import com.sesac.gojigi.user.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

/**
 * 인증 API — 게이트웨이에서 공개 경로(/api/auth/**). Access Token 은 body, Refresh Token 은 HttpOnly 쿠키 (컨벤션 4-4).
 * 쿠키: HttpOnly · Secure · SameSite=Strict · Path=/api/auth — 프론트 JS 가 읽지 못하고 /api/auth/** 요청에만 실린다.
 */
@Tag(name = "인증", description = "회원가입 · 로그인 · 토큰 재발급 · 로그아웃 — 인가: 공개 (Keycloak 대리 호출)")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final String refreshCookieName;
    private final String refreshCookiePath;
    private final boolean refreshCookieSecure;

    public AuthController(AuthService authService,
            @Value("${app.auth.refresh-cookie-name:refresh_token}") String refreshCookieName,
            @Value("${app.auth.refresh-cookie-path}") String refreshCookiePath,
            @Value("${app.auth.refresh-cookie-secure:true}") boolean refreshCookieSecure) {
        this.authService = authService;
        this.refreshCookieName = refreshCookieName;
        this.refreshCookiePath = refreshCookiePath;
        this.refreshCookieSecure = refreshCookieSecure;
    }

    @Operation(summary = "로그인", description = """
            인가: 공개. Access Token 은 body(data.accessToken), Refresh Token 은 Set-Cookie refresh_token
            (HttpOnly · Secure · SameSite=Strict · Path=/api/auth — 프론트 JS 로 읽을 수 없음).
            에러: 400 INVALID_INPUT · 401 AUTH_INVALID_CREDENTIALS(비밀번호 불일치 · 없는 계정 · 임시 잠금)
            · 401 AUTH_ACCOUNT_NOT_LINKED · 403 AUTH_ACCOUNT_DISABLED · 502 EXTERNAL_API_ERROR(인증 서버 장애)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공 — Set-Cookie 로 Refresh Token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT — 이메일 형식 · 빈 값"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_INVALID_CREDENTIALS · AUTH_ACCOUNT_NOT_LINKED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_ACCOUNT_DISABLED — 이용 정지"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "EXTERNAL_API_ERROR — Keycloak 연결 실패 · 서킷 OPEN")
    })
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        LoginResult result = authService.login(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken(), result.refreshExpiresIn()).toString())
                .body(ApiResponse.success("로그인되었습니다.", result.response()));
    }

    @Operation(summary = "이메일 중복 확인", description = """
            인가: 공개. available=false 면 이미 가입된 이메일 (탈퇴 회원 포함). 대소문자 구분 없음.
            에러: 400 INVALID_INPUT(이메일 형식)""")
    @GetMapping("/email-availability")
    public ApiResponse<EmailAvailabilityResponse> emailAvailability(@Valid @ModelAttribute EmailAvailabilityRequest request) {
        String email = request.normalizedEmail();
        boolean available = authService.isEmailAvailable(email);
        return ApiResponse.success(available ? "사용할 수 있는 이메일입니다." : "이미 가입된 이메일입니다.",
                new EmailAvailabilityResponse(email, available));
    }

    @Operation(summary = "회원가입", description = """
            인가: 공개. 가입 후 자동 로그인 — data.login 은 로그인 응답과 같고 Refresh Token 은 Set-Cookie refresh_token.
            data.login 이 null 이면 가입은 됐지만 자동 로그인 실패 → 로그인 화면으로 (쿠키 없음).
            에러: 400 INVALID_INPUT(형식) · 400 AUTH_PASSWORD_POLICY_VIOLATION · 409 AUTH_EMAIL_DUPLICATE
            · 502 EXTERNAL_API_ERROR(인증 서버 장애 — 가입 안 됨)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "가입 완료 — 자동 로그인 성공 시 Set-Cookie 로 Refresh Token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT · AUTH_PASSWORD_POLICY_VIOLATION"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "AUTH_EMAIL_DUPLICATE — 이미 가입된 이메일"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "EXTERNAL_API_ERROR — Keycloak 연결 실패 · 서킷 OPEN")
    })
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<SignupResponse>> signup(@Valid @RequestBody SignupRequest request) {
        SignupResult result = authService.signup(request);
        if (result.login() == null) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success("회원가입이 완료되었습니다. 로그인해 주세요.", new SignupResponse(result.userId(), null)));
        }
        LoginResult login = result.login();
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, refreshCookie(login.refreshToken(), login.refreshExpiresIn()).toString())
                .body(ApiResponse.success("회원가입이 완료되었습니다.", new SignupResponse(result.userId(), login.response())));
    }

    @Operation(summary = "로그아웃", description = """
            인가: 공개(Refresh 쿠키로 처리 — Authorization 헤더 불필요). Keycloak 세션을 끝내고 refresh_token 쿠키를 지운다(Max-Age=0).
            쿠키가 없거나 이미 끝난 세션이어도 200 — 여러 번 눌러도 같다. 프론트는 메모리의 Access Token 을 버린다.""")
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, refreshCookieName);
        authService.logout(cookie == null ? null : cookie.getValue());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", 0).toString())   // 같은 이름 · 경로로 덮어써야 브라우저가 지운다
                .body(ApiResponse.success("로그아웃되었습니다."));
    }

    private ResponseCookie refreshCookie(String refreshToken, long maxAgeSeconds) {
        return ResponseCookie.from(refreshCookieName, refreshToken)
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Strict")
                .path(refreshCookiePath)
                .maxAge(Duration.ofSeconds(maxAgeSeconds))   // Keycloak refresh_expires_in (세션 14일) · 로그아웃은 0 (삭제)
                .build();
    }
}
