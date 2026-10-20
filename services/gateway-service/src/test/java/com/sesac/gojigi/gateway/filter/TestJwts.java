package com.sesac.gojigi.gateway.filter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** 테스트용 Keycloak 토큰 — realm 매퍼가 넣는 user_id · store_id · roles 모양 그대로 */
final class TestJwts {

    private TestJwts() {
    }

    static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claims(c -> c.putAll(claims))
                .issuedAt(Instant.parse("2026-10-20T00:00:00Z"))
                .expiresAt(Instant.parse("2026-10-20T00:30:00Z"))
                .build();
    }

    static AbstractAuthenticationToken authentication(Map<String, Object> claims) {
        return new JwtAuthenticationToken(jwt(claims), List.of(new SimpleGrantedAuthority("ROLE_BUYER")));
    }

    static Map<String, Object> seller() {
        return Map.of("sub", "kc-2", "user_id", 2L, "store_id", 1L,
                "roles", List.of("default-roles-gotgan", "offline_access", "BUYER", "uma_authorization", "SELLER"));
    }

    static Map<String, Object> buyer() {
        return Map.of("sub", "kc-4", "user_id", 4L, "roles", List.of("default-roles-gotgan", "BUYER"));
    }
}
