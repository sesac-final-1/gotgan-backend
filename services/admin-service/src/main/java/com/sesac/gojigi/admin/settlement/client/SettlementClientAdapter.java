package com.sesac.gojigi.admin.settlement.client;

import com.sesac.gojigi.admin.common.client.RemoteCalls;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/**
 * settlement-service 호출 — 서킷 브레이커 "settlement", 재시도는 GET 만(집계 · 지급 · 보류 · 수수료 변경은 재시도 없음 —
 * 지급은 같은 ids 를 다시 보내도 이중 지급이 없지만 관리자가 결과를 보고 다시 누르게 둔다). 오류는 RemoteCalls 규칙으로 그대로 전달
 */
@Component
@RequiredArgsConstructor
public class SettlementClientAdapter {

    private static final String TARGET = "settlement";

    private final SettlementClient settlementClient;

    @Retry(name = TARGET)
    @CircuitBreaker(name = TARGET, fallbackMethod = "listFallback")
    public JsonNode list(String week, Long storeId, String status) {
        return call(() -> settlementClient.list(week, storeId, status));
    }

    @Retry(name = TARGET)
    @CircuitBreaker(name = TARGET, fallbackMethod = "detailFallback")
    public JsonNode detail(Long id) {
        return call(() -> settlementClient.detail(id));
    }

    @CircuitBreaker(name = TARGET, fallbackMethod = "bodyFallback")
    public JsonNode aggregate(JsonNode body) {
        return call(() -> settlementClient.aggregate(body));
    }

    @CircuitBreaker(name = TARGET, fallbackMethod = "bodyFallback")
    public JsonNode pay(JsonNode body) {
        return call(() -> settlementClient.pay(body));
    }

    @CircuitBreaker(name = TARGET, fallbackMethod = "holdFallback")
    public JsonNode hold(Long id, JsonNode body) {
        return call(() -> settlementClient.hold(id, body));
    }

    @Retry(name = TARGET)
    @CircuitBreaker(name = TARGET, fallbackMethod = "noArgFallback")
    public JsonNode commissions() {
        return call(settlementClient::commissions);
    }

    @CircuitBreaker(name = TARGET, fallbackMethod = "bodyFallback")
    public JsonNode updateCommissions(JsonNode body) {
        return call(() -> settlementClient.updateCommissions(body));
    }

    /** 관리자 대시보드 — 응답의 platform 영역만 */
    @Retry(name = TARGET)
    @CircuitBreaker(name = TARGET, fallbackMethod = "noArgFallback")
    public JsonNode platformStats() {
        return call(settlementClient::platformStats).path("platform");
    }

    private static JsonNode call(Supplier<ApiResponse<JsonNode>> request) {
        try {
            return request.get().data();
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.relay(TARGET, e);
        }
    }

    // 폴백 — 이름이 겹치면(오버로드) Resilience4j 가 고르지 못해 인자 모양마다 따로. 이미 바꾼 4xx 는 그대로, 장애는 502
    JsonNode listFallback(String week, Long storeId, String status, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, t);
    }

    JsonNode detailFallback(Long id, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, t);
    }

    JsonNode bodyFallback(JsonNode body, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, t);
    }

    JsonNode holdFallback(Long id, JsonNode body, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, t);
    }

    JsonNode noArgFallback(Throwable t) {
        throw RemoteCalls.unavailable(TARGET, t);
    }
}
