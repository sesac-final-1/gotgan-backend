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
 * TODO 이경석: 클레임 추출 · 헤더 주입 구현. 지금은 클라이언트 헤더 제거만 한다.
 */
@Component
public class UserHeaderFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerWebExchange stripped = exchange.mutate()
                .request(r -> r.headers(h -> {
                    h.remove("X-User-Id");
                    h.remove("X-User-Roles");
                    h.remove("X-Store-Id");
                }))
                .build();
        return chain.filter(stripped);
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
