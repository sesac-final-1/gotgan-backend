package com.sesac.gojigi.user.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 소셜 로그인 한 번의 상태 — 시작할 때 만들어 HttpOnly 쿠키에 담고, 콜백에서 꺼내 비교한다.
 * - state: CSRF 방지 — Keycloak 이 콜백에 그대로 돌려준 값과 쿠키 값이 같아야 한다
 * - codeVerifier: PKCE — 가로챈 code 로는 토큰을 받을 수 없게 한다 (Keycloak 에는 SHA-256 challenge 만 보낸다)
 * 쿠키 값 형식: {provider alias}.{state}.{codeVerifier} — 세 값 모두 '.' 이 없는 문자만 쓴다 (alias 영문, 나머지 Base64URL)
 */
public record SocialLoginState(SocialProvider provider, String state, String codeVerifier) {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    public static SocialLoginState start(SocialProvider provider) {
        return new SocialLoginState(provider, random(32), random(48));
    }

    /** 쿠키 값을 되돌린다. 형식이 틀리거나 모르는 제공자면 null */
    public static SocialLoginState fromCookieValue(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new SocialLoginState(SocialProvider.fromAlias(parts[0]), parts[1], parts[2]);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public String cookieValue() {
        return provider.alias() + "." + state + "." + codeVerifier;
    }

    /** PKCE S256 — Base64URL(SHA-256(codeVerifier)) */
    public String codeChallenge() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return BASE64_URL.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);   // JDK 필수 알고리즘이라 일어나지 않음
        }
    }

    public boolean matches(String returnedState) {
        return returnedState != null
                && MessageDigest.isEqual(state.getBytes(StandardCharsets.US_ASCII), returnedState.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public String toString() {
        return "SocialLoginState[provider=" + provider + ", state=***, codeVerifier=***]";
    }

    private static String random(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return BASE64_URL.encodeToString(value);
    }
}
