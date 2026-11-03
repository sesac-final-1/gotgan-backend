package com.sesac.gojigi.user.user.client;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * order-service 호출 — 탈퇴 전 진행 중인 주문 · 교환 · 반품 확인. 서킷 브레이커 "order", 조회(GET)라 재시도 1회.
 * 확인하지 못하면 탈퇴를 막는다 — 거절(4xx) · 장애 모두 EXTERNAL_API_ERROR(502).
 */
@Component
@RequiredArgsConstructor
public class OrderClientAdapter {

    private static final String CIRCUIT = "order";

    private final OrderClient orderClient;

    /** 구매자의 진행 중 상품주문 수 — 진행 중 클레임이 걸린 상품주문 포함 */
    @Retry(name = CIRCUIT)
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "countInProgressFallback")
    public long countInProgress(Long buyerId) {
        try {
            return orderClient.getBuyerStats(buyerId).data().inProgressCount();
        } catch (HttpClientErrorException e) {
            throw new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR,
                    "주문을 확인하지 못했습니다. buyerId=" + buyerId + ", status=" + e.getStatusCode().value(), e);
        }
    }

    long countInProgressFallback(Long buyerId, Throwable t) {
        if (t instanceof BusinessException business) {
            throw business;   // 4xx 거절 — 이미 변환됨
        }
        // 연결 실패 · 타임아웃 · 5xx · 서킷 OPEN(CallNotPermittedException)
        throw new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "주문 서버에 연결할 수 없습니다.", t);
    }
}
