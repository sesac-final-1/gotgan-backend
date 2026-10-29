package com.sesac.gojigi.store.store.client;

import com.sesac.gojigi.store.store.client.dto.SettlementOutstandingResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/** settlement-service 호출 — 서킷 브레이커 "settlement", 조회(GET)라 재시도 1회. 실패 변환 규칙은 RemoteCalls */
@Component
@RequiredArgsConstructor
public class SettlementClientAdapter {

    private static final String CIRCUIT = "settlement";

    private final SettlementClient settlementClient;

    /** 미정산 현황 — clear=false 면 폐점 거절 */
    @Retry(name = CIRCUIT)
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "outstandingFallback")
    public SettlementOutstandingResponse outstanding(Long storeId) {
        try {
            return settlementClient.outstanding(storeId).data();
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.rejected("settlement", "미정산 조회", e);
        }
    }

    SettlementOutstandingResponse outstandingFallback(Long storeId, Throwable t) {
        throw RemoteCalls.unavailable("settlement", "미정산 조회", t);
    }
}
