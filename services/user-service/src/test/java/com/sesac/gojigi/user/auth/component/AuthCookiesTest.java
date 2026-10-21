package com.sesac.gojigi.user.auth.component;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** 인증 쿠키 — 이름 · 경로 · SameSite · 수명 (설정값은 생성자로) */
class AuthCookiesTest {

    private final AuthCookies cookies = new AuthCookies("social_login", "refresh_token", "/api/auth", true);

    @Test
    @DisplayName("Refresh 쿠키 — HttpOnly · Secure · SameSite=Strict · Path=/api/auth, 수명은 Keycloak refresh_expires_in")
    void refresh() {
        assertThat(cookies.refresh("rt", 1209600))
                .startsWith("refresh_token=rt; Path=/api/auth; Max-Age=1209600;")
                .endsWith("; Secure; HttpOnly; SameSite=Strict");
        assertThat(cookies.clearRefresh()).startsWith("refresh_token=; Path=/api/auth; Max-Age=0;");
    }

    @Test
    @DisplayName("소셜 state 쿠키 — SameSite=Lax · Path=/api/auth/social · 10분, 콜백에서 Max-Age=0 으로 삭제")
    void social() {
        assertThat(cookies.social("state-value"))
                .startsWith("social_login=state-value; Path=/api/auth/social; Max-Age=600;")
                .endsWith("; Secure; HttpOnly; SameSite=Lax");
        assertThat(cookies.clearSocial()).startsWith("social_login=; Path=/api/auth/social; Max-Age=0;");
    }

    @Test
    @DisplayName("Secure 끄기(로컬 http) — Secure 속성만 빠진다")
    void insecure() {
        AuthCookies local = new AuthCookies("social_login", "refresh_token", "/api/auth", false);

        assertThat(local.refresh("rt", 60)).doesNotContain("Secure").contains("HttpOnly");
        assertThat(local.social("s")).doesNotContain("Secure").contains("HttpOnly");
    }

    @Test
    @DisplayName("요청 쿠키 읽기 — 없으면 null")
    void read() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThat(cookies.refreshToken(request)).isNull();
        assertThat(cookies.socialState(request)).isNull();

        request.setCookies(new Cookie("refresh_token", "rt"), new Cookie("social_login", "st"));
        assertThat(cookies.refreshToken(request)).isEqualTo("rt");
        assertThat(cookies.socialState(request)).isEqualTo("st");
    }
}
