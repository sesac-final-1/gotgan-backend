package com.sesac.gojigi.gateway.exception;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 401 — 토큰 없음 · 서명 불일치 · 만료 · issuer 불일치. 응답은 업무 서비스와 같은 봉투 */
@Component
@RequiredArgsConstructor
public class GatewayAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private final GatewayErrorResponder responder;

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        return responder.write(exchange, HttpStatus.UNAUTHORIZED, GatewayErrorResponder.UNAUTHORIZED, "인증이 필요합니다.");
    }
}
