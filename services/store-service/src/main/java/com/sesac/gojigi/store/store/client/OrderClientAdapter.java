package com.sesac.gojigi.store.store.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/** order-service 호출 — 서킷 브레이커 "order", 조회(GET)라 재시도 1회. 실패 변환 규칙은 RemoteCalls */
@Component
@RequiredArgsConstructor
public class OrderClientAdapter {

    private static final String CIRCUIT = "order";

    private final OrderClient orderClient;

    /** 진행 중 상품주문 수 (ORD-10 inProgressCount) — 1 이상이면 폐점 거절 */
    @Retry(name = CIRCUIT)
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "inProgressCountFallback")
    public long inProgressCount(Long storeId) {
        try {
            return orderClient.storeStats(storeId).data().inProgressCount();
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.rejected("order", "주문 통계 조회", e);
        }
    }

    long inProgressCountFallback(Long storeId, Throwable t) {
        throw RemoteCalls.unavailable("order", "주문 통계 조회", t);
    }
}
