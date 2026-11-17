package com.sesac.gojigi.gateway.config;

import java.io.IOException;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.RouteToRequestUrlFilter;
import org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory.RetryConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 업무 서비스 연결 실패 재시도 (11/17 장애 주입 500 분석).
 * 파드가 처리 중에 죽거나(장애 주입 · OOM) 엔드포인트에서 아직 안 빠진 죽은 파드로 연결하면 요청이 실패한다.
 * GET 만 최대 2번 다시 보낸다 — Service(kube-proxy)가 다시 고르므로 대부분 살아 있는 파드로 간다.
 * - 응답을 받기 전 연결 실패 · 끊김(IOException: 연결 거부 · 연결 타임아웃 · PrematureCloseException)일 때만
 * - 5xx 응답 · 응답 타임아웃은 재시도하지 않는다 — 느린 요청을 겹쳐 보내 부하를 키우지 않게
 * - POST 같은 쓰기는 중복 처리 위험이라 제외
 * yml default-filters 로 두면 라우트 필터(요청 제한)보다 바깥이라 재시도마다 토큰을 또 쓴다 → 전역 필터로 요청 제한 뒤 · 라우팅 직전에 둔다.
 */
@Configuration
public class UpstreamRetryConfig {

    static final int RETRIES = 2;
    /** 라우트 필터(요청 제한 등, 순서 1 · 2 …) 뒤, 요청 주소 결정(RouteToRequestUrlFilter) 직전 */
    static final int ORDER = RouteToRequestUrlFilter.ROUTE_TO_URL_FILTER_ORDER - 1;

    @Bean
    GlobalFilter upstreamRetryFilter(RetryGatewayFilterFactory factory) {
        RetryConfig config = new RetryConfig()
                .setRetries(RETRIES)
                .setMethods(HttpMethod.GET)
                .setSeries()
                .setStatuses()
                .setExceptions(IOException.class);
        return new OrderedRetry(factory.apply(config));
    }

    record OrderedRetry(GatewayFilter retry) implements GlobalFilter, Ordered {
        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            return retry.filter(exchange, chain);
        }

        @Override
        public int getOrder() {
            return ORDER;
        }
    }
}
