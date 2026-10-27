package com.sesac.gojigi.chat.realtime;

import com.sesac.gojigi.common.web.auth.AuthUser;
import java.security.Principal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * STOMP 연결의 사용자 — CONNECT 때 검증한 Keycloak 토큰 클레임(user_id · roles · store_id, 게이트웨이 AuthenticatedUser 와 같은 규칙).
 * expiresAt: 토큰 만료 시각. 지나면 SEND · SUBSCRIBE 를 거절하고 ChatSessionRegistry 가 연결을 닫는다 → 화면이 새 토큰으로 다시 연결
 */
public record ChatPrincipal(Long userId, List<String> roles, Long storeId, Instant expiresAt) implements Principal {

    private static final Set<String> SERVICE_ROLES = Set.of("BUYER", "SELLER", "ADMIN");

    /** user_id 가 없으면(회원과 연결 안 된 Keycloak 계정) null — 게이트웨이처럼 거절 */
    static ChatPrincipal from(Jwt jwt) {
        Long userId = longClaim(jwt, "user_id");
        if (userId == null) {
            return null;
        }
        Object roles = jwt.getClaims().get("roles");
        List<String> serviceRoles = roles instanceof Collection<?> values
                ? values.stream().map(String::valueOf).filter(SERVICE_ROLES::contains).toList()
                : List.of();
        return new ChatPrincipal(userId, serviceRoles, longClaim(jwt, "store_id"), jwt.getExpiresAt());
    }

    /** @SendToUser 의 사용자 이름 — 오류는 보낸 연결(session)에만 가므로 겹쳐도 상관없다 */
    @Override
    public String getName() {
        return String.valueOf(userId);
    }

    public AuthUser toAuthUser() {
        return new AuthUser(userId, roles, storeId);
    }

    boolean expired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    private static Long longClaim(Jwt jwt, String name) {
        Object value = jwt.getClaims().get(name);
        return value == null ? null : Long.valueOf(value.toString());
    }
}
