package com.sesac.gojigi.admin.product.client;

import com.sesac.gojigi.admin.common.client.RemoteCalls;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/** product-service 호출 — 서킷 브레이커 "product"(카테고리와 같은 대상), 재시도는 조회만. 오류는 RemoteCalls 규칙으로 그대로 전달 */
@Component
@RequiredArgsConstructor
public class AdminProductClientAdapter {

    private static final String TARGET = "product";

    private final AdminProductClient adminProductClient;

    @Retry(name = TARGET)
    @CircuitBreaker(name = TARGET, fallbackMethod = "productsFallback")
    public JsonNode products(Long storeId) {
        return call(() -> adminProductClient.products(storeId));
    }

    @CircuitBreaker(name = TARGET, fallbackMethod = "blockFallback")
    public JsonNode block(Long productId, JsonNode body) {
        return call(() -> adminProductClient.block(productId, body));
    }

    /** 관리자 대시보드 — 응답의 platform 영역만 */
    @Retry(name = TARGET)
    @CircuitBreaker(name = TARGET, fallbackMethod = "platformStatsFallback")
    public JsonNode platformStats() {
        return call(adminProductClient::platformStats).get("platform");   // 없으면 null (MissingNode 는 직렬화하지 않게)
    }

    private static JsonNode call(Supplier<ApiResponse<JsonNode>> request) {
        try {
            return request.get().data();
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.relay(TARGET, e);
        }
    }

    // 폴백 — 이미 바꾼 4xx 는 그대로, 장애는 502
    JsonNode productsFallback(Long storeId, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, t);
    }

    JsonNode blockFallback(Long productId, JsonNode body, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, t);
    }

    JsonNode platformStatsFallback(Throwable t) {
        throw RemoteCalls.unavailable(TARGET, t);
    }
}
