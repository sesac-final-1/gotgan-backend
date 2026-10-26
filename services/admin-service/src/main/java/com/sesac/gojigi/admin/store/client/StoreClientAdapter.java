package com.sesac.gojigi.admin.store.client;

import com.sesac.gojigi.admin.common.client.RemoteCalls;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/** store-service 호출 — 서킷 브레이커 "store", 재시도는 GET 만(승인 · 반려 · 보완요청 · 정지는 재시도 없음 — store.md 공통). 오류는 그대로 전달 */
@Component
@RequiredArgsConstructor
public class StoreClientAdapter {

    private static final String TARGET = "store";

    private final StoreClient storeClient;

    @Retry(name = TARGET)
    @CircuitBreaker(name = TARGET, fallbackMethod = "statusFallback")
    public JsonNode applications(String status) {
        return call(() -> storeClient.applications(status));
    }

    @Retry(name = TARGET)
    @CircuitBreaker(name = TARGET, fallbackMethod = "idFallback")
    public JsonNode application(Long applicationId) {
        return call(() -> storeClient.application(applicationId));
    }

    @CircuitBreaker(name = TARGET, fallbackMethod = "idFallback")
    public JsonNode approve(Long applicationId) {
        return call(() -> storeClient.approve(applicationId));
    }

    @CircuitBreaker(name = TARGET, fallbackMethod = "idBodyFallback")
    public JsonNode reject(Long applicationId, JsonNode body) {
        return call(() -> storeClient.reject(applicationId, body));
    }

    @CircuitBreaker(name = TARGET, fallbackMethod = "idBodyFallback")
    public JsonNode supplement(Long applicationId, JsonNode body) {
        return call(() -> storeClient.supplement(applicationId, body));
    }

    @Retry(name = TARGET)
    @CircuitBreaker(name = TARGET, fallbackMethod = "statusFallback")
    public JsonNode stores(String status) {
        return call(() -> storeClient.stores(status));
    }

    @Retry(name = TARGET)
    @CircuitBreaker(name = TARGET, fallbackMethod = "idFallback")
    public JsonNode store(Long storeId) {
        return call(() -> storeClient.store(storeId));
    }

    @CircuitBreaker(name = TARGET, fallbackMethod = "idBodyFallback")
    public JsonNode changeStoreStatus(Long storeId, JsonNode body) {
        return call(() -> storeClient.changeStoreStatus(storeId, body));
    }

    private static JsonNode call(Supplier<ApiResponse<JsonNode>> request) {
        try {
            return request.get().data();
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.relay(TARGET, e);
        }
    }

    // 폴백 — 인자 모양마다 이름을 따로(같은 이름 오버로드는 Resilience4j 가 고르지 못함). 이미 바꾼 4xx 는 그대로, 장애는 502
    JsonNode statusFallback(String status, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, t);
    }

    JsonNode idFallback(Long id, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, t);
    }

    JsonNode idBodyFallback(Long id, JsonNode body, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, t);
    }
}
