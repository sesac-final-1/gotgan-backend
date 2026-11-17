package com.sesac.gojigi.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 접속 신호 (11/17, 현재 접속자를 바로 늘리고 줄이기) — 업무 서비스로 보내지 않고 게이트웨이가 바로 204.
 * - POST /api/presence/ping: 쇼핑몰 탭이 보이는 동안 20초마다. 접속자로 기록(ActiveUserFilter 와 같은 키)
 * - POST /api/presence/leave: 탭을 닫거나 숨길 때(fetch keepalive). 접속자에서 바로 뺀다
 * 비로그인도 보내므로 SecurityConfig 에서 permitAll. 보안 필터 뒤라 토큰이 있으면 회원으로 센다
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class PresenceWebFilter implements WebFilter {

    static final String PING = "/api/presence/ping";
    static final String LEAVE = "/api/presence/leave";

    private final ActiveUserFilter activeUsers;

    public PresenceWebFilter(ActiveUserFilter activeUsers) {
        this.activeUsers = activeUsers;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        boolean ping = PING.equals(path);
        boolean leave = LEAVE.equals(path);
        if ((!ping && !leave) || exchange.getRequest().getMethod() != HttpMethod.POST) {
            return chain.filter(exchange);
        }
        String visitor = ActiveUserFilter.visitorKey(exchange);
        return ActiveUserFilter.memberKey()
                .doOnNext(member -> {
                    String m = member.isEmpty() ? null : member;
                    if (ping) {
                        activeUsers.record(visitor, m);
                    } else {
                        activeUsers.forget(visitor, m);
                    }
                })
                .then(Mono.fromRunnable(() -> exchange.getResponse().setStatusCode(HttpStatus.NO_CONTENT)))
                .then(exchange.getResponse().setComplete());
    }
}
