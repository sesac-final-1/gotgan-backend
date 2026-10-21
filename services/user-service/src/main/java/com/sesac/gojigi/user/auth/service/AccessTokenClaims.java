package com.sesac.gojigi.user.auth.service;

import com.sesac.gojigi.user.auth.exception.AuthErrorCode;
import com.sesac.gojigi.user.auth.exception.AuthException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Access Token 의 필요한 클레임만 꺼낸다 — sub(Keycloak 사용자 ID) · user_id · store_id · roles · email · name(소셜 첫 로그인 회원 생성용).
 * 서명 검증은 하지 않는다: user-service 가 방금 Keycloak 에서 클러스터 내부로 직접 받은 토큰이라서다.
 * 브라우저가 보낸 토큰은 게이트웨이가 JWKS 로 검증한다 (이 클래스를 그 용도로 쓰면 안 된다).
 */
public record AccessTokenClaims(String keycloakId, Long userId, Long storeId, List<String> roles, String email, String name) {

    /** 서비스가 쓰는 역할만 — default-roles-gotgan · offline_access · uma_authorization 같은 Keycloak 내부 역할 제외 */
    private static final Set<String> SERVICE_ROLES = Set.of("BUYER", "SELLER", "ADMIN");

    public static AccessTokenClaims parse(String accessToken, JsonMapper jsonMapper) {
        String[] parts = accessToken == null ? new String[0] : accessToken.split("\\.");
        if (parts.length < 2) {
            throw new AuthException(AuthErrorCode.AUTH_ACCOUNT_NOT_LINKED);
        }
        JsonNode payload = jsonMapper.readTree(
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));

        List<String> roles = new ArrayList<>();
        payload.path("roles").forEach(role -> {
            if (SERVICE_ROLES.contains(role.asString())) {
                roles.add(role.asString());
            }
        });
        return new AccessTokenClaims(
                payload.path("sub").asString(null),
                longOrNull(payload.path("user_id")),
                longOrNull(payload.path("store_id")),
                List.copyOf(roles),
                payload.path("email").asString(null),
                displayName(payload));
    }

    /** 이름 — name(이름 전체) → given_name(이름만, 카카오 닉네임 매퍼) → preferred_username 순 */
    private static String displayName(JsonNode payload) {
        for (String claim : List.of("name", "given_name", "preferred_username")) {
            String value = payload.path(claim).asString(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Long longOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asLong();
    }
}
