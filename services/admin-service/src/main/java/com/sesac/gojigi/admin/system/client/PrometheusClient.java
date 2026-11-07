package com.sesac.gojigi.admin.system.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Prometheus HTTP API(/api/v1/query) — 지표는 보조 정보라 실패해도 오류를 던지지 않고 null(화면은 "-").
 * 연결 · 읽기 제한을 짧게(기본 2초) — 15초 폴링이 쌓이지 않게
 */
@Slf4j
@Component
public class PrometheusClient {

    private final RestClient restClient;

    public PrometheusClient(RestClient.Builder restClientBuilder,
            @Value("${app.prometheus.url}") String baseUrl,
            @Value("${app.prometheus.timeout:2s}") Duration timeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.restClient = restClientBuilder.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** 즉시 쿼리 — 결과 vector 를 routeId 라벨 → 값으로. 실패 · 오류 응답이면 빈 Optional */
    public Optional<Map<String, Double>> byRoute(String promql) {
        JsonNode body;
        try {
            body = restClient.get().uri(u -> u.path("/api/v1/query").queryParam("query", "{q}").build(promql))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("Prometheus 조회 실패 - {}", e.getMessage());
            return Optional.empty();
        }
        if (body == null || !"success".equals(body.path("status").asString(null))) {
            log.warn("Prometheus 오류 응답 - {}", body);
            return Optional.empty();
        }
        Map<String, Double> values = new HashMap<>();
        for (JsonNode series : body.path("data").path("result")) {
            String routeId = series.path("metric").path("routeId").asString(null);
            double value = Double.parseDouble(series.path("value").path(1).asString("0"));
            if (routeId != null && Double.isFinite(value)) {
                values.merge(routeId, value, Double::sum);
            }
        }
        return Optional.of(values);
    }
}
