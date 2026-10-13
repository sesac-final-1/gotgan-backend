package com.sesac.gojigi.product.common.auth;

import java.util.List;

/**
 * 로그인 사용자 — 게이트웨이가 JWT 에서 꺼내 X-User-Id · X-User-Roles · X-Store-Id 헤더로 넣어 준 값.
 * 서비스에는 SecurityContext 가 없다. 컨트롤러는 userId · storeId 원시값을 서비스로 넘긴다 (컨벤션 2-4 · 2-6).
 */
public record AuthUser(Long userId, List<String> roles, Long storeId) {

    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }
}
