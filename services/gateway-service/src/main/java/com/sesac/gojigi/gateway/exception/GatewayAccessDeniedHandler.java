package com.sesac.gojigi.gateway.exception;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 403 — 로그인은 했지만 경로의 역할이 없음 (예: 구매자가 /api/seller/**). 응답은 업무 서비스와 같은 봉투 */
@Component
@RequiredArgsConstructor
public class GatewayAccessDeniedHandler implements ServerAccessDeniedHandler {

    private final GatewayErrorResponder responder;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        return responder.write(exchange, HttpStatus.FORBIDDEN, GatewayErrorResponder.FORBIDDEN, "권한이 없습니다.");
    }
}
