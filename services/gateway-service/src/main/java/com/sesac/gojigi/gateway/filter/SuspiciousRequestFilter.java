package com.sesac.gojigi.gateway.filter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 탐색 · 공격 의심 요청 기록 (11/17 장애 분석 리포트) — 막지 않고 남기기만 한다. 응답은 원래대로(대부분 404 · 401).
 * 인터넷에 열린 서버에는 /.env · /.git · /wp-admin 같은 경로를 훑는 자동 스캐너가 계속 들어온다.
 * 리포트가 "공격이 들어왔는가"를 판단할 수 있게 WARN 로그(Loki) + 카운터 gateway_suspicious_requests_total{type}(Prometheus) 를 남긴다.
 * 보안 필터보다 먼저 봐야 401 로 끝나는 요청도 센다 → 가장 앞 순서.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SuspiciousRequestFilter implements WebFilter {

    static final String METRIC = "gateway.suspicious.requests";
    private static final int MAX_LOGGED_PATH = 200;

    /** 유형 → 경로(소문자) 판별. 위에서부터 처음 맞는 유형 하나로 센다 */
    private static final List<Map.Entry<String, Predicate<String>>> RULES = List.of(
            Map.entry("secret-file", p -> p.contains("/.env") || p.contains("/.git") || p.contains("/.aws") || p.contains("/.ssh")
                    || p.endsWith("/config.json") || p.endsWith("/credentials")),
            Map.entry("traversal", p -> p.contains("../") || p.contains("..%2f") || p.contains("%2e%2e")),
            Map.entry("cms-probe", p -> p.contains("/wp-") || p.contains("phpmyadmin") || p.contains("xmlrpc")),
            Map.entry("script-probe", p -> p.endsWith(".php") || p.endsWith(".asp") || p.endsWith(".aspx") || p.contains("/cgi-bin")));

    private final Map<String, Counter> counters;

    public SuspiciousRequestFilter(MeterRegistry registry) {
        this.counters = RULES.stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                rule -> Counter.builder(METRIC).tag("type", rule.getKey()).register(registry)));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String raw = exchange.getRequest().getURI().getRawPath();
        String type = classify(raw);
        if (type != null) {
            counters.get(type).increment();
            log.warn("의심 요청 type={} method={} path={} source={}", type, exchange.getRequest().getMethod(), sanitize(raw),
                    RateLimitKeyResolver.anonymousKey(exchange.getRequest().getHeaders()));
        }
        return chain.filter(exchange);
    }

    /** 의심 유형, 정상 경로면 null */
    static String classify(String rawPath) {
        if (rawPath == null) {
            return null;
        }
        String path = rawPath.toLowerCase(Locale.ROOT);
        return RULES.stream().filter(rule -> rule.getValue().test(path)).map(Map.Entry::getKey).findFirst().orElse(null);
    }

    /** 로그 주입(줄바꿈) 막고 길이 제한 — 경로는 공격자가 마음대로 넣는 값 */
    static String sanitize(String path) {
        String oneLine = path.replaceAll("[\\r\\n\\t]", "_");
        return oneLine.length() > MAX_LOGGED_PATH ? oneLine.substring(0, MAX_LOGGED_PATH) + "..." : oneLine;
    }
}
