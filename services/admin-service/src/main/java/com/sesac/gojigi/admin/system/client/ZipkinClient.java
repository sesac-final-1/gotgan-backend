package com.sesac.gojigi.admin.system.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Zipkin API(/api/v2) — 관리자 화면 실시간 호출 목록 · 트레이스(11/16). 샘플링 1.0 이라 요청이 전부 남는다(메모리 저장, 재시작하면 비워짐).
 * 보조 정보라 실패해도 오류를 던지지 않고 빈 목록
 */
@Slf4j
@Component
public class ZipkinClient {

    public static final String GATEWAY = "gateway-service";

    private final RestClient restClient;

    public ZipkinClient(RestClient.Builder restClientBuilder, @Value("${app.monitor.zipkin-url}") String baseUrl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.restClient = restClientBuilder.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** 게이트웨이를 지난 최근 트레이스(최신순) — annotationQuery 가 있으면 그 태그 조건(예: outcome=SERVER_ERROR)만 */
    public List<JsonNode> recentTraces(String annotationQuery, long endTsMs, long lookbackMs, int limit) {
        try {
            JsonNode body = restClient.get().uri(u -> {
                u.path("/api/v2/traces").queryParam("serviceName", GATEWAY).queryParam("spanName", "all")
                        .queryParam("endTs", endTsMs).queryParam("lookback", lookbackMs).queryParam("limit", limit);
                if (annotationQuery != null) {
                    u.queryParam("annotationQuery", "{a}");
                    return u.build(annotationQuery);
                }
                return u.build();
            }).retrieve().body(JsonNode.class);
            List<JsonNode> traces = new ArrayList<>();
            if (body != null) {
                body.forEach(traces::add);
            }
            return traces;
        } catch (RestClientException e) {
            log.warn("Zipkin 조회 실패 - {}", e.getMessage());
            return List.of();
        }
    }

    /** 트레이스 하나의 구간(span) 전부 — 없거나 실패면 빈 목록 */
    public List<JsonNode> trace(String traceId) {
        try {
            JsonNode body = restClient.get().uri("/api/v2/trace/{id}", traceId).retrieve().body(JsonNode.class);
            List<JsonNode> spans = new ArrayList<>();
            if (body != null) {
                body.forEach(spans::add);
            }
            return spans;
        } catch (RestClientException e) {
            log.warn("Zipkin 트레이스 조회 실패 - traceId={}, {}", traceId, e.getMessage());
            return List.of();
        }
    }
}
