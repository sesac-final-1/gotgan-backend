package com.sesac.gojigi.gateway.exception;

import java.net.ConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 게이트웨이 처리 중 예외 → 업무 서비스와 같은 봉투 { code, message, data: null } (A-경-6c · 컨벤션 2-5).
 * 없으면 Spring 기본 오류 JSON(timestamp · path · error)이 나가 401 · 403 과 모양이 달랐다.
 * - 업무 서비스 연결 실패(파드 없음 · 연결 거부 · 연결 타임아웃) → 502 EXTERNAL_API_ERROR
 * - 응답 타임아웃(httpclient.response-timeout, 게이트웨이가 504 ResponseStatusException 으로 바꿈) → 504 EXTERNAL_API_ERROR
 * - 맞는 라우트 없음 → 404 NOT_FOUND, 그 밖의 상태 코드 예외는 상태 그대로
 * - 그 밖의 예외 → 500 GLOBAL_ERROR
 * 401 · 403 은 보안 핸들러, 429 는 요청 제한 필터가 각자 응답해서 여기로 오지 않는다.
 * 기본 핸들러(DefaultErrorWebExceptionHandler, @Order(-1))보다 먼저 받는다. 로그는 여기서 한 번만(컨벤션 5-6).
 */
@Slf4j
@Component
@Order(-2)
@RequiredArgsConstructor
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private final GatewayErrorResponder responder;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);   // 이미 응답을 보내기 시작함(스트리밍 중 끊김 등) — 덮어쓸 수 없다
        }
        if (isConnectFailure(ex)) {
            log.warn("업무 서비스 연결 실패 route={} path={} cause={}", routeId(exchange), path(exchange), ex.toString());
            return responder.write(exchange, HttpStatus.BAD_GATEWAY, GatewayErrorResponder.EXTERNAL_API_ERROR,
                    "서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
        if (ex instanceof ResponseStatusException rse) {
            return writeStatus(exchange, rse.getStatusCode(), rse);
        }
        log.error("게이트웨이 처리 중 오류 route={} path={}", routeId(exchange), path(exchange), ex);
        return responder.write(exchange, HttpStatus.INTERNAL_SERVER_ERROR, GatewayErrorResponder.GLOBAL_ERROR,
                "서버 오류가 발생했습니다.");
    }

    private Mono<Void> writeStatus(ServerWebExchange exchange, HttpStatusCode code, ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(code.value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return switch (status) {
            case GATEWAY_TIMEOUT -> {
                log.warn("업무 서비스 응답 타임아웃 route={} path={} cause={}", routeId(exchange), path(exchange), ex.getReason());
                yield responder.write(exchange, status, GatewayErrorResponder.EXTERNAL_API_ERROR,
                        "서비스 응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.");
            }
            case BAD_GATEWAY, SERVICE_UNAVAILABLE -> {
                log.warn("업무 서비스 호출 실패 route={} path={} status={} cause={}", routeId(exchange), path(exchange),
                        status.value(), ex.getReason());
                yield responder.write(exchange, status, GatewayErrorResponder.EXTERNAL_API_ERROR,
                        "서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.");
            }
            case NOT_FOUND -> responder.write(exchange, status, GatewayErrorResponder.NOT_FOUND,
                    "요청한 리소스를 찾을 수 없습니다.");
            case METHOD_NOT_ALLOWED -> responder.write(exchange, status, GatewayErrorResponder.METHOD_NOT_ALLOWED,
                    "지원하지 않는 요청 방식입니다.");
            default -> status.is4xxClientError()
                    ? responder.write(exchange, status, GatewayErrorResponder.INVALID_INPUT, "입력값이 올바르지 않습니다.")
                    : serverError(exchange, status, ex);
        };
    }

    private Mono<Void> serverError(ServerWebExchange exchange, HttpStatus status, Throwable ex) {
        log.error("게이트웨이 처리 중 오류 route={} path={} status={}", routeId(exchange), path(exchange), status.value(), ex);
        return responder.write(exchange, status, GatewayErrorResponder.GLOBAL_ERROR, "서버 오류가 발생했습니다.");
    }

    /** 원인 사슬에 연결 실패가 있는가 — Netty 의 연결 거부 · 연결 타임아웃 예외는 ConnectException 의 하위 */
    static boolean isConnectFailure(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }

    private static String routeId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route == null ? "-" : route.getId();
    }

    private static String path(ServerWebExchange exchange) {
        return exchange.getRequest().getPath().value();
    }
}
