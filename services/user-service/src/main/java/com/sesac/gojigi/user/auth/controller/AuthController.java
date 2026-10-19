package com.sesac.gojigi.user.auth.controller;

import com.sesac.gojigi.user.auth.dto.request.LoginRequest;
import com.sesac.gojigi.user.auth.dto.response.LoginResponse;
import com.sesac.gojigi.user.auth.service.AuthService;
import com.sesac.gojigi.user.auth.service.LoginResult;
import com.sesac.gojigi.user.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 API — 게이트웨이에서 공개 경로(/api/auth/**). Access Token 은 body, Refresh Token 은 HttpOnly 쿠키 (컨벤션 4-4).
 * 쿠키: HttpOnly · Secure · SameSite=Strict · Path=/api/auth — 프론트 JS 가 읽지 못하고 /api/auth/** 요청에만 실린다.
 */
@Tag(name = "인증", description = "로그인 · 토큰 재발급 · 로그아웃 — 인가: 공개 (Keycloak 대리 호출)")
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

    private ResponseCookie refreshCookie(String refreshToken, long maxAgeSeconds) {
        return ResponseCookie.from(refreshCookieName, refreshToken)
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Strict")
                .path(refreshCookiePath)
                .maxAge(Duration.ofSeconds(maxAgeSeconds))   // Keycloak refresh_expires_in (세션 14일)
                .build();
    }
}
