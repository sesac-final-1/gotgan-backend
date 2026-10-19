package com.sesac.gojigi.user.auth.controller;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** app.auth.refresh-cookie-* — Refresh Token 쿠키 (HttpOnly · SameSite=Strict 는 고정, 이름 · 경로 · Secure 만 설정) */
@ConfigurationProperties(prefix = "app.auth")
public record RefreshCookieProperties(
        @DefaultValue("refresh_token") String refreshCookieName,
        @DefaultValue("/api/auth") String refreshCookiePath,
        @DefaultValue("true") boolean refreshCookieSecure) {
}
