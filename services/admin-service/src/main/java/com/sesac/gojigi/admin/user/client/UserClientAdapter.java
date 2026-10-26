package com.sesac.gojigi.admin.user.client;

import com.sesac.gojigi.admin.common.client.RemoteCalls;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/** user-service 호출 — 서킷 브레이커 "user", 재시도는 GET 3개만(정지 · 해제 PATCH 는 재시도 없음). 오류는 RemoteCalls 규칙으로 그대로 전달 */
@Component
@RequiredArgsConstructor
public class UserClientAdapter {

    private static final String TARGET = "user";

    private final UserClient userClient;

    @Retry(name = TARGET)
    @CircuitBreaker(name = TARGET, fallbackMethod = "listFallback")
    public JsonNode list(Long adminId, String q, String status, Integer page, Integer size) {
        return call(() -> userClient.list(adminId, q, status, page, size));
    }

    @Retry(name = TARGET)
    @CircuitBreaker(name = TARGET, fallbackMethod = "detailFallback")
    public JsonNode detail(Long adminId, Long userId) {
        return call(() -> userClient.detail(adminId, userId));
    }

    @Retry(name = TARGET)
    @CircuitBreaker(name = TARGET, fallbackMethod = "pointsFallback")
    public JsonNode points(Long adminId, Long userId, Integer page, Integer size) {
        return call(() -> userClient.points(adminId, userId, page, size));
    }

    @CircuitBreaker(name = TARGET, fallbackMethod = "changeStatusFallback")
    public JsonNode changeStatus(Long adminId, Long userId, JsonNode body) {
        return call(() -> userClient.changeStatus(adminId, userId, body));
    }

    private static JsonNode call(Supplier<ApiResponse<JsonNode>> request) {
        try {
            return request.get().data();
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.relay(TARGET, e);
        }
    }

    // 폴백 — 이름이 겹치면 Resilience4j 가 고르지 못해 메서드마다 따로. 이미 바꾼 4xx 는 그대로, 장애는 502
    JsonNode listFallback(Long adminId, String q, String status, Integer page, Integer size, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, t);
    }

    JsonNode detailFallback(Long adminId, Long userId, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, t);
    }

    JsonNode pointsFallback(Long adminId, Long userId, Integer page, Integer size, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, t);
    }

    JsonNode changeStatusFallback(Long adminId, Long userId, JsonNode body, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, t);
    }
}
