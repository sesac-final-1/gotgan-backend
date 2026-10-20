package com.sesac.gojigi.gateway.filter;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

/**
 * 인증된 요청의 JWT 클레임 — Keycloak realm 설정의 매퍼가 넣는 user_id(long) · store_id(long, 판매자만) · roles(realm 역할).
 * 공개 경로 · 토큰 없는 요청이면 비어 있다.
 */
record AuthenticatedUser(String userId, List<String> roles, String storeId) {

    /** 업무에 쓰는 역할만 헤더로 넘긴다 (default-roles-gotgan · offline_access 등 Keycloak 기본 역할 제외) */
    private static final Set<String> SERVICE_ROLES = Set.of("BUYER", "SELLER", "ADMIN");

    static Mono<AuthenticatedUser> current() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(authentication -> from(((JwtAuthenticationToken) authentication).getToken()));
    }

    static AuthenticatedUser from(Jwt jwt) {
        return new AuthenticatedUser(claimAsString(jwt, "user_id"), serviceRoles(jwt), claimAsString(jwt, "store_id"));
    }

    /** user_id 가 없으면(Keycloak 계정이 userdb 회원과 연결 안 됨) 헤더를 만들지 않는다 → 업무 서비스 401 */
    boolean linked() {
        return userId != null;
    }

    private static String claimAsString(Jwt jwt, String name) {
        Object value = jwt.getClaims().get(name);
        return value == null ? null : value.toString();
    }

    private static List<String> serviceRoles(Jwt jwt) {
        Object roles = jwt.getClaims().get("roles");
        if (!(roles instanceof Collection<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).filter(SERVICE_ROLES::contains).toList();
    }
}
