package com.sesac.gojigi.admin.system.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loki API — 관리자 화면 장애 목록의 에러 로그(11/16). 서비스가 Loki4j 로 보낸 JSON 로그(라벨 app, level, 본문 message, traceId).
 * 보조 정보라 실패해도 오류를 던지지 않고 빈 목록
 */
@Slf4j
@Component
public class LokiClient {

    public static final String ERROR_QUERY = "{namespace=\"sesac-gojigi\",level=~\"WARN|ERROR\"}";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient restClient;

    public LokiClient(RestClient.Builder restClientBuilder, @Value("${app.monitor.loki-url}") String baseUrl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.restClient = restClientBuilder.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** 최근 WARN, ERROR 로그(최신순, 최대 limit) — service 가 있으면 그 서비스(app 라벨)만 */
    public List<LogLine> errors(Instant start, Instant end, int limit, String service) {
        return lines(service == null ? ERROR_QUERY : ERROR_QUERY.replace("}", ",app=\"" + service + "\"}"), start, end, limit);
    }

    /**
     * LogQL 지표 쿼리(count_over_time 등)를 구간별로 — 장애 리포트의 시간대별 에러 로그, DB 오류 건수.
     * label 값 → (구간 끝 시각 ms → 값). 실패면 빈 Map
     */
    public Map<String, Map<Long, Double>> countBy(String logql, String label, Instant start, Instant end, Duration step) {
        JsonNode body;
        try {
            body = restClient.get().uri(u -> u.path("/loki/api/v1/query_range").queryParam("query", "{q}")
                    .queryParam("start", start.toEpochMilli() * 1_000_000).queryParam("end", end.toEpochMilli() * 1_000_000)
                    .queryParam("step", step.toSeconds()).build(logql)).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("Loki 지표 조회 실패 - {}", e.getMessage());
            return Map.of();
        }
        if (body == null) {
            return Map.of();
        }
        Map<String, Map<Long, Double>> result = new HashMap<>();
        for (JsonNode series : body.path("data").path("result")) {
            Map<Long, Double> points = result.computeIfAbsent(series.path("metric").path(label).asString(""), k -> new HashMap<>());
            for (JsonNode point : series.path("values")) {
                points.merge(Math.round(point.path(0).asDouble() * 1000), Double.parseDouble(point.path(1).asString("0")), Double::sum);
            }
        }
        return result;
    }

    /** 로그 줄 조회(최신순, 최대 limit) — 실패면 빈 목록 */
    public List<LogLine> lines(String query, Instant start, Instant end, int limit) {
        JsonNode body;
        try {
            body = restClient.get().uri(u -> u.path("/loki/api/v1/query_range").queryParam("query", "{q}")
                    .queryParam("start", start.toEpochMilli() * 1_000_000).queryParam("end", end.toEpochMilli() * 1_000_000)
                    .queryParam("limit", limit).queryParam("direction", "backward").build(query)).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("Loki 조회 실패 - {}", e.getMessage());
            return List.of();
        }
        if (body == null) {
            return List.of();
        }
        List<LogLine> lines = new ArrayList<>();
        for (JsonNode stream : body.path("data").path("result")) {
            String app = stream.path("stream").path("app").asString("");
            String level = stream.path("stream").path("level").asString("");
            for (JsonNode value : stream.path("values")) {
                lines.add(line(app, level, Long.parseLong(value.path(0).asString("0")) / 1_000_000, value.path(1).asString("")));
            }
        }
        lines.sort(Comparator.comparingLong(LogLine::timestampMs).reversed());
        return lines.size() > limit ? lines.subList(0, limit) : lines;
    }

    /** 로그 한 줄 — JSON 이면 message, traceId 를 꺼내고, 아니면 줄 전체를 메시지로 */
    static LogLine line(String service, String level, long timestampMs, String raw) {
        try {
            JsonNode json = JSON.readTree(raw);
            return new LogLine(timestampMs, service, level, json.path("message").asString(raw), json.path("traceId").asString(null));
        } catch (JacksonException e) {
            return new LogLine(timestampMs, service, level, raw, null);
        }
    }

    public record LogLine(long timestampMs, String service, String level, String message, String traceId) {
    }
}
