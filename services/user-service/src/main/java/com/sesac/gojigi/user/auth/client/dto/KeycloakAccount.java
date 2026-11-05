package com.sesac.gojigi.user.auth.client.dto;

/** Keycloak 계정과 user_id 속성 — userId 는 속성이 없으면(소셜 첫 로그인 전) null */
public record KeycloakAccount(String keycloakId, Long userId) {
}
