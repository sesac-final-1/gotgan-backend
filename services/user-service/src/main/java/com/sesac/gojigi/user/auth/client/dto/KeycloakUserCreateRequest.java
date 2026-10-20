package com.sesac.gojigi.user.auth.client.dto;

import java.util.List;
import java.util.Map;

/**
 * Keycloak UserRepresentation 중 가입에 필요한 필드만.
 * username = email (realm 설정 — 로그인 ID 가 이메일), user_id 속성은 User Profile 에 선언된 관리자 전용 속성.
 * 이름 · 휴대폰의 주인은 userdb 라 Keycloak 에는 넣지 않는다.
 */
public record KeycloakUserCreateRequest(
        String username,
        String email,
        boolean enabled,
        Map<String, List<String>> attributes,
        List<Credential> credentials) {

    public record Credential(String type, String value, boolean temporary) {

        @Override
        public String toString() {
            return "Credential[type=" + type + ", value=***, temporary=" + temporary + "]";   // 비밀번호 로그 금지 (컨벤션 5-6)
        }
    }

    public static KeycloakUserCreateRequest of(String email, String password, long userId) {
        return new KeycloakUserCreateRequest(email, email, true,
                Map.of("user_id", List.of(String.valueOf(userId))),
                List.of(new Credential("password", password, false)));
    }
}
