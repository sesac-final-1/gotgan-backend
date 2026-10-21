package com.sesac.gojigi.user.auth.component;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

/**
 * 인증 쿠키 읽기 · 만들기 — 이름 · 경로 · Secure 는 설정(app.auth.*)에서 받는다 (컨벤션 4-4).
 * - Refresh Token: HttpOnly · Secure · SameSite=Strict · Path=/api/auth — 프론트 JS 가 읽지 못하고 /api/auth/** 요청에만 실린다
 * - 소셜 로그인 state · PKCE: HttpOnly · SameSite=Lax · Path=/api/auth/social · 10분 — Keycloak(다른 사이트)에서 돌아오는 이동에도 실려야 해서 Lax
 * 지우기는 같은 이름 · 경로에 Max-Age=0 으로 덮어쓴다 (경로가 다르면 브라우저가 지우지 않음).
 */
@Component
public class AuthCookies {

    private static final String SOCIAL_COOKIE_PATH = "/api/auth/social";
    private static final Duration SOCIAL_COOKIE_MAX_AGE = Duration.ofMinutes(10);   // 제공자 로그인을 마치고 돌아올 시간

    private final String socialCookieName;
    private final String refreshCookieName;
    private final String refreshCookiePath;
    private final boolean secure;

    public AuthCookies(@Value("${app.auth.social.cookie-name:social_login}") String socialCookieName,
            @Value("${app.auth.refresh-cookie-name:refresh_token}") String refreshCookieName,
            @Value("${app.auth.refresh-cookie-path}") String refreshCookiePath,
            @Value("${app.auth.refresh-cookie-secure:true}") boolean secure) {
        this.socialCookieName = socialCookieName;
        this.refreshCookieName = refreshCookieName;
        this.refreshCookiePath = refreshCookiePath;
        this.secure = secure;
    }

    /** 요청의 Refresh Token — 없으면 null */
    public String refreshToken(HttpServletRequest request) {
        return value(request, refreshCookieName);
    }

    /** 요청의 소셜 로그인 state 쿠키 값 — 없으면 null */
    public String socialState(HttpServletRequest request) {
        return value(request, socialCookieName);
    }

    /** Refresh Token 쿠키 — 수명은 Keycloak refresh_expires_in (세션 14일) */
    public String refresh(String refreshToken, long maxAgeSeconds) {
        return refreshCookie(refreshToken, Duration.ofSeconds(maxAgeSeconds));
    }

    /** 로그아웃 — Refresh Token 쿠키 삭제 */
    public String clearRefresh() {
        return refreshCookie("", Duration.ZERO);
    }

    /** 소셜 로그인 시작 — state · PKCE 쿠키 (10분) */
    public String social(String value) {
        return socialCookie(value, SOCIAL_COOKIE_MAX_AGE);
    }

    /** 소셜 로그인 콜백 — state 는 한 번만 쓰므로 삭제 */
    public String clearSocial() {
        return socialCookie("", Duration.ZERO);
    }

    private String refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(refreshCookieName, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(refreshCookiePath)
                .maxAge(maxAge)
                .build()
                .toString();
    }

    private String socialCookie(String value, Duration maxAge) {
        return ResponseCookie.from(socialCookieName, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(SOCIAL_COOKIE_PATH)
                .maxAge(maxAge)
                .build()
                .toString();
    }

    private static String value(HttpServletRequest request, String name) {
        Cookie cookie = WebUtils.getCookie(request, name);
        return cookie == null ? null : cookie.getValue();
    }
}
