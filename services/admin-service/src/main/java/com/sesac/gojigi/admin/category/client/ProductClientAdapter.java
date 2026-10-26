package com.sesac.gojigi.admin.category.client;

import com.sesac.gojigi.admin.common.client.RemoteCalls;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/** product-service 호출 — 서킷 브레이커 "product", 재시도는 조회만. 오류는 RemoteCalls 규칙으로 그대로 전달 */
@Component
@RequiredArgsConstructor
public class ProductClientAdapter {

    private static final String TARGET = "product";

    private final ProductClient productClient;

    @Retry(name = TARGET)
    @CircuitBreaker(name = TARGET, fallbackMethod = "categoriesFallback")
    public JsonNode categories() {
        return call(productClient::categories);
    }

    @CircuitBreaker(name = TARGET, fallbackMethod = "createFallback")
    public JsonNode create(JsonNode body) {
        return call(() -> productClient.create(body));
    }

    @CircuitBreaker(name = TARGET, fallbackMethod = "idBodyFallback")
    public JsonNode update(Long id, JsonNode body) {
        return call(() -> productClient.update(id, body));
    }

    @CircuitBreaker(name = TARGET, fallbackMethod = "idBodyFallback")
    public JsonNode changeActive(Long id, JsonNode body) {
        return call(() -> productClient.changeActive(id, body));
    }

    private static JsonNode call(Supplier<ApiResponse<JsonNode>> request) {
        try {
            return request.get().data();
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.relay(TARGET, e);
        }
    }

    // 폴백 — 이미 바꾼 4xx 는 그대로, 장애는 502
    JsonNode categoriesFallback(Throwable t) {
        throw RemoteCalls.unavailable(TARGET, t);
    }

    JsonNode createFallback(JsonNode body, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, t);
    }

    JsonNode idBodyFallback(Long id, JsonNode body, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, t);
    }
}
