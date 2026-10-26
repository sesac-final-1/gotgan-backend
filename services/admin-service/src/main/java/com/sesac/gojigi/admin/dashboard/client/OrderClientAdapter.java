package com.sesac.gojigi.admin.dashboard.client;

import com.sesac.gojigi.admin.common.client.RemoteCalls;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/** order-service 호출 — 서킷 브레이커 "order", 조회라 재시도. 오류는 RemoteCalls 규칙 (대시보드는 실패를 부분 응답으로 바꾼다) */
@Component
@RequiredArgsConstructor
public class OrderClientAdapter {

    private static final String TARGET = "order";

    private final OrderClient orderClient;

    @Retry(name = TARGET)
    @CircuitBreaker(name = TARGET, fallbackMethod = "platformStatsFallback")
    public JsonNode platformStats() {
        try {
            return orderClient.platformStats().data();
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.relay(TARGET, e);
        }
    }

    JsonNode platformStatsFallback(Throwable t) {
        throw RemoteCalls.unavailable(TARGET, t);
    }
}
