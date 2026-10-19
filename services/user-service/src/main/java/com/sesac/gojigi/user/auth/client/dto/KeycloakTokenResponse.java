package com.sesac.gojigi.user.auth.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Keycloak 토큰 응답 — 필요한 필드만 (만료는 초 단위) */
public record KeycloakTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("refresh_expires_in") long refreshExpiresIn) {
}
