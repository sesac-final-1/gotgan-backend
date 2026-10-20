package com.sesac.gojigi.user.auth.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** client_credentials 토큰 응답 — 서비스 계정 토큰엔 refresh_token 이 없어 KeycloakTokenResponse 와 나눈다 */
public record KeycloakServiceAccountToken(@JsonProperty("access_token") String accessToken) {
}
