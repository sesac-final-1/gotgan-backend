package com.sesac.gojigi.order.order.client;

import com.sesac.gojigi.order.order.client.dto.PointUseRequest;
import com.sesac.gojigi.order.order.client.dto.UserSummaryResponse;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.exception.OrderException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * user-service 호출 — 서킷 브레이커 "user". 재시도는 조회(GET)만, 포인트 사용 · 취소(POST · DELETE)는 Saga 가 sagaId 로 다시 부른다.
 * 실패 변환 규칙은 RemoteCalls.
 */
@Component
@RequiredArgsConstructor
public class UserClientAdapter {

    private static final String CIRCUIT = "user";
    private static final String TARGET = "user";

    private final UserClient userClient;

    /** 주문자 스냅샷용 회원 조회 — 없는 회원 · 탈퇴는 ORDER_BUYER_NOT_FOUND */
    @Retry(name = CIRCUIT)
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "getUserFallback")
    public UserSummaryResponse getUser(Long userId) {
        try {
            return userClient.getUser(userId).data();
        } catch (HttpClientErrorException.NotFound e) {
            throw new OrderException(OrderErrorCode.ORDER_BUYER_NOT_FOUND);
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.rejected(TARGET, "회원 조회", e);
        }
    }

    /** 포인트 사용 (Saga POINT_USED) — 잔액 부족은 ORDER_POINT_INSUFFICIENT */
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "usePointsFallback")
    public void usePoints(PointUseRequest request) {
        try {
            userClient.usePoints(request);
        } catch (HttpClientErrorException e) {
            if ("POINT_INSUFFICIENT".equals(RemoteCalls.errorCode(e))) {
                throw new OrderException(OrderErrorCode.ORDER_POINT_INSUFFICIENT);
            }
            if (e instanceof HttpClientErrorException.NotFound) {
                throw new OrderException(OrderErrorCode.ORDER_BUYER_NOT_FOUND);
            }
            throw RemoteCalls.rejected(TARGET, "포인트 사용", e);
        }
    }

    /** 포인트 사용 취소 (Saga 보상) — 되돌릴 사용이 없어도 성공 */
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "restorePointsFallback")
    public void restorePoints(String sagaId) {
        try {
            userClient.restorePoints(sagaId);
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.rejected(TARGET, "포인트 사용 취소", e);
        }
    }

    UserSummaryResponse getUserFallback(Long userId, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, "회원 조회", t);
    }

    void usePointsFallback(PointUseRequest request, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, "포인트 사용", t);
    }

    void restorePointsFallback(String sagaId, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, "포인트 사용 취소", t);
    }
}
