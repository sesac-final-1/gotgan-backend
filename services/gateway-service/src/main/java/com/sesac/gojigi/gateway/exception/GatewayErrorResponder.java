package com.sesac.gojigi.gateway.exception;

import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * 게이트웨이가 직접 내는 오류 응답 — 업무 서비스와 같은 봉투 { code, message, data: null } (컨벤션 2-4 · 2-5).
 * 필터 체인(WebFlux) 안에서는 BusinessException 을 던지지 않고 이걸로 바로 쓴다.
 */
@Component
@RequiredArgsConstructor
public class GatewayErrorResponder {

    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";

    private final JsonMapper jsonMapper;

    record ErrorBody(String code, String message, Object data) {
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = jsonMapper.writeValueAsString(new ErrorBody(code, message, null)).getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
