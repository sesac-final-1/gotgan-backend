package com.sesac.gojigi.user.user.client;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * store-service 호출 — 판매자 탈퇴 전 스토어 폐점 확인. 서킷 브레이커 "store", 조회(GET)라 재시도 1회 (store.md 11절).
 * 폐점을 확인하지 못하면 탈퇴를 막는다 — 없는 스토어(404) · 그 밖의 거절 · 장애 모두 EXTERNAL_API_ERROR(502).
 */
@Component
@RequiredArgsConstructor
public class StoreClientAdapter {

    private static final String CIRCUIT = "store";

    private final StoreClient storeClient;

    @Retry(name = CIRCUIT)
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "getStoreStatusFallback")
    public StoreStatus getStoreStatus(Long storeId) {
        try {
            return storeClient.getStore(storeId).data().status();
        } catch (HttpClientErrorException e) {
            throw new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR,
                    "스토어를 확인하지 못했습니다. storeId=" + storeId + ", status=" + e.getStatusCode().value(), e);
        }
    }

    StoreStatus getStoreStatusFallback(Long storeId, Throwable t) {
        if (t instanceof BusinessException business) {
            throw business;   // 4xx 거절 — 이미 변환됨
        }
        // 연결 실패 · 타임아웃 · 5xx · 서킷 OPEN(CallNotPermittedException)
        throw new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "스토어 서버에 연결할 수 없습니다.", t);
    }
}
