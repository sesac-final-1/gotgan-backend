package com.sesac.gojigi.gateway.filter;

import io.netty.channel.ChannelOption;
import java.net.URI;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.tcp.TcpClient;

/**
 * WebSocket 연결 전에 업무 서비스에 붙을 수 있는지 먼저 확인 (11/19 chat 장애 시연).
 * WebsocketRoutingFilter 는 브라우저와의 연결을 먼저 성립(101)시킨 뒤 업무 서비스에 붙는다. 그래서 chat 파드가 없으면
 * 실패가 오류 응답, 로그, 5xx 지표 어디에도 남지 않고 연결만 조용히 닫혀, 시스템 상태와 장애 리포트가 채팅 장애를 보지 못했다.
 * 업그레이드 직전에 같은 주소로 TCP 연결을 한 번 열어 보고, 실패하면 연결 예외를 넘긴다
 * → GatewayErrorWebExceptionHandler 가 REST 와 똑같이 "업무 서비스 연결 실패" 로그 + 502, 지표는 routeId=chat-websocket 5xx.
 * 클라이언트는 재연결할 때마다 502 를 받으므로 chat 이 내려가 있는 동안 계속 기록된다. 확인 비용은 연결 한 번(클러스터 안, 수 ms).
 */
@Component
public class WebSocketUpstreamCheckFilter implements GlobalFilter, Ordered {

    /** 요청 주소 결정(RouteToRequestUrlFilter, 10000) 뒤, WebSocket 라우팅(WebsocketRoutingFilter, LOWEST_PRECEDENCE - 1) 직전 */
    static final int ORDER = Ordered.LOWEST_PRECEDENCE - 2;
    /** 게이트웨이 httpclient.connect-timeout 과 같게 */
    static final int CONNECT_TIMEOUT_MS = 2000;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        URI target = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        if (target == null || !"websocket".equalsIgnoreCase(exchange.getRequest().getHeaders().getUpgrade())) {
            return chain.filter(exchange);
        }
        return TcpClient.create()
                .host(target.getHost())
                .port(port(target))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .connect()
                .doOnNext(Connection::dispose)
                .then(Mono.defer(() -> chain.filter(exchange)));
    }

    static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
