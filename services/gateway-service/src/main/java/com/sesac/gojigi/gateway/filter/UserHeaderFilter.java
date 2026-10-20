package com.sesac.gojigi.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 클라이언트가 보낸 X-User-* 헤더를 제거하고, JWT 클레임(user_id · roles · store_id) 으로 다시 붙인다 (가이드라인 08장).
 * 업무 서비스는 이 헤더를 믿는다 — 전제는 "게이트웨이를 거치지 않으면 닿지 않는다"(ClusterIP).
 * 공개 경로 · user_id 없는 토큰이면 지우기만 한다 → 헤더가 필요한 API 는 업무 서비스가 401.
 */
@Component
public class UserHeaderFilter implements GlobalFilter, Ordered {

    static final String USER_ID = "X-User-Id";
    static final String USER_ROLES = "X-User-Roles";
    static final String STORE_ID = "X-Store-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerWebExchange stripped = exchange.mutate()
                .request(r -> r.headers(h -> {
                    h.remove(USER_ID);
                    h.remove(USER_ROLES);
                    h.remove(STORE_ID);
                }))
                .build();
        return AuthenticatedUser.current()
                .filter(AuthenticatedUser::linked)
                .map(user -> withUserHeaders(stripped, user))
                .defaultIfEmpty(stripped)
                .flatMap(chain::filter);
    }

    private static ServerWebExchange withUserHeaders(ServerWebExchange exchange, AuthenticatedUser user) {
        return exchange.mutate()
                .request(r -> r.headers(h -> {
                    h.set(USER_ID, user.userId());
                    h.set(USER_ROLES, String.join(",", user.roles()));
                    if (user.storeId() != null) {
                        h.set(STORE_ID, user.storeId());
                    }
                }))
                .build();
    }

    @Override
    public int getOrder() {
        return -1;   // 라우팅 필터보다 먼저. 차단 목록 확인(BlockedUserFilter, -2) 다음
    }
}
