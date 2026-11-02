package com.sesac.gojigi.review.inquiry.client;

import com.sesac.gojigi.review.inquiry.client.dto.UserSummaryResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/** user-service 호출 — 서킷 브레이커 "user", 조회(GET)라 재시도 1회. 실패 변환 규칙은 RemoteCalls */
@Component
@RequiredArgsConstructor
public class UserClientAdapter {

    private static final String CIRCUIT = "user";
    private static final String ACTION = "회원 조회";
    private static final String MESSAGE = "회원 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.";

    private final UserClient userClient;

    /** 문의 작성자 — 로그인한 회원이라 4xx(탈퇴 등)는 예상 밖이므로 502 */
    @Retry(name = CIRCUIT)
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "getUserFallback")
    public UserSummaryResponse getUser(Long userId) {
        try {
            return userClient.getUser(userId).data();
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.rejected(CIRCUIT, ACTION, MESSAGE, e);
        }
    }

    UserSummaryResponse getUserFallback(Long userId, Throwable t) {
        throw RemoteCalls.unavailable(CIRCUIT, ACTION, MESSAGE, t);
    }
}
