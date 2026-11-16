package com.sesac.gojigi.admin.system.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;
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

    /**
     * 범위 쿼리(/api/v1/query_range) — 부하테스트 그래프. 시리즈 하나(sum · max 로 묶은 쿼리)의 [시각 ms, 값] 목록.
     * 결과가 없으면 빈 목록, 실패 · 오류 응답이면 빈 Optional
     */
    public Optional<List<Sample>> range(String promql, Instant start, Instant end, Duration step) {
        JsonNode body;
        try {
            body = restClient.get().uri(u -> u.path("/api/v1/query_range").queryParam("query", "{q}")
                    .queryParam("start", start.getEpochSecond()).queryParam("end", end.getEpochSecond())
                    .queryParam("step", step.toSeconds()).build(promql)).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("Prometheus 범위 조회 실패 - {}", e.getMessage());
            return Optional.empty();
        }
        if (body == null || !"success".equals(body.path("status").asString(null))) {
            log.warn("Prometheus 범위 조회 오류 응답 - {}", body);
            return Optional.empty();
        }
        List<Sample> samples = new ArrayList<>();
        for (JsonNode point : body.path("data").path("result").path(0).path("values")) {
            double value = Double.parseDouble(point.path(1).asString("0"));
            if (Double.isFinite(value)) {
                samples.add(new Sample(Math.round(point.path(0).asDouble() * 1000), value));
            }
        }
        return Optional.of(samples);
    }

    public record Sample(long t, double v) {
    }

    /** 범위 쿼리 — label 값별 시리즈(모니터링 서비스별 그래프). label 이 없는 시리즈는 "" 로. 실패면 빈 Optional */
    public Optional<Map<String, List<Sample>>> rangeBy(String promql, String label, Instant start, Instant end, Duration step) {
        return fetch(u -> u.path("/api/v1/query_range").queryParam("query", "{q}").queryParam("start", start.getEpochSecond())
                .queryParam("end", end.getEpochSecond()).queryParam("step", step.toSeconds()).build(promql)).map(result -> {
                    Map<String, List<Sample>> series = new HashMap<>();
                    for (JsonNode s : result) {
                        List<Sample> samples = new ArrayList<>();
                        for (JsonNode point : s.path("values")) {
                            double value = Double.parseDouble(point.path(1).asString("0"));
                            if (Double.isFinite(value)) {
                                samples.add(new Sample(Math.round(point.path(0).asDouble() * 1000), value));
                            }
                        }
                        series.put(s.path("metric").path(label).asString(""), samples);
                    }
                    return series;
                });
    }

    /** 즉시 쿼리 — 시리즈마다 라벨 전체와 값(분석 TOP 5 · DB 연결 풀). NaN 은 뺀다. 실패면 빈 Optional */
    public Optional<List<Series>> vector(String promql) {
        return fetch(u -> u.path("/api/v1/query").queryParam("query", "{q}").build(promql)).map(result -> {
            List<Series> series = new ArrayList<>();
            for (JsonNode s : result) {
                double value = Double.parseDouble(s.path("value").path(1).asString("0"));
                if (Double.isFinite(value)) {
                    Map<String, String> labels = new HashMap<>();
                    s.path("metric").properties().forEach(e -> labels.put(e.getKey(), e.getValue().asString("")));
                    series.add(new Series(labels, value));
                }
            }
            return series;
        });
    }

    public record Series(Map<String, String> labels, double value) {
    }

    /** data.result 노드 — 실패 · 오류 응답이면 빈 Optional */
    private Optional<JsonNode> fetch(Function<UriBuilder, URI> uri) {
        JsonNode body;
        try {
            body = restClient.get().uri(uri).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("Prometheus 조회 실패 - {}", e.getMessage());
            return Optional.empty();
        }
        if (body == null || !"success".equals(body.path("status").asString(null))) {
            log.warn("Prometheus 오류 응답 - {}", body);
            return Optional.empty();
        }
        return Optional.of(body.path("data").path("result"));
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
