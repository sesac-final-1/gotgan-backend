package com.sesac.gojigi.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtRolesConverterTest {

    @Test
    @DisplayName("Keycloak roles 클레임 → ROLE_* 권한 (hasRole 판단 기준)")
    void convert_rolesClaim_toRoleAuthorities() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256")
                .claim("sub", "kc-2").claim("roles", List.of("default-roles-gotgan", "BUYER", "SELLER"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();

        List<String> authorities = SecurityConfig.jwtAuthenticationConverter().convert(jwt).block()
                .getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();

        assertThat(authorities).contains("ROLE_BUYER", "ROLE_SELLER").doesNotContain("SCOPE_profile");
    }
}
