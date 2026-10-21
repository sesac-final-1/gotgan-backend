package com.sesac.gojigi.user.auth.service;

/** 소셜 로그인 시작 — 브라우저를 보낼 Keycloak 로그인 주소 · state 쿠키 값 */
public record SocialLoginStart(String authorizationUrl, String cookieValue) {

    @Override
    public String toString() {
        return "SocialLoginStart[authorizationUrl=" + authorizationUrl + ", cookieValue=***]";
    }
}
