package com.sesac.gojigi.admin.review.client;

import com.sesac.gojigi.admin.common.client.RemoteCalls;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** review-service 호출 — 서킷 브레이커 "review", 재시도는 GET 만(블라인드 PATCH 는 재시도 없음) */
@Component
@RequiredArgsConstructor
public class ReviewClientAdapter implements ReviewReader {

    private static final String TARGET = "review";

    private final ReviewClient reviewClient;
    private final JsonMapper jsonMapper;

    @Override
    @Retry(name = TARGET)
    @CircuitBreaker(name = TARGET, fallbackMethod = "getFallback")
    public JsonNode get(Long reviewId) {
        return call(() -> reviewClient.get(reviewId));
    }

    @Override
    @CircuitBreaker(name = TARGET, fallbackMethod = "blindFallback")
    public JsonNode blind(Long reviewId, boolean blinded, String reason) {
        return call(() -> reviewClient.blind(reviewId, jsonMapper.createObjectNode().put("blinded", blinded).put("reason", reason)));
    }

    @Override
    @Retry(name = TARGET)
    @CircuitBreaker(name = TARGET, fallbackMethod = "listByUserFallback")
    public JsonNode listByUser(Long userId, Integer page, Integer size) {
        return call(() -> reviewClient.listByUser(userId, page, size));
    }

    private static JsonNode call(Supplier<ApiResponse<JsonNode>> request) {
        try {
            return request.get().data();
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.relay(TARGET, e);
        }
    }

    // 폴백 — 이름이 겹치면 Resilience4j 가 고르지 못해 메서드마다 따로. 이미 바꾼 4xx 는 그대로, 장애는 502
    JsonNode getFallback(Long reviewId, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, t);
    }

    JsonNode blindFallback(Long reviewId, boolean blinded, String reason, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, t);
    }

    JsonNode listByUserFallback(Long userId, Integer page, Integer size, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, t);
    }
}
