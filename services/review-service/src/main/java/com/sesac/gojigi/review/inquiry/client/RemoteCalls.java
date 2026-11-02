package com.sesac.gojigi.review.inquiry.client;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.HttpClientErrorException;

/**
 * 다른 서비스 호출 실패를 review 예외로 바꾸는 공통 규칙 (store · order RemoteCalls 와 같은 규칙):
 * - 4xx: 어댑터가 아는 코드(없는 상품 404)는 도메인 예외, 나머지는 호출 쪽 버그라 warn + 502 — 서킷 실패로 세지 않음(BusinessException)
 * - 연결 실패 · 타임아웃 · 5xx · 서킷 OPEN: 폴백에서 502 — 화면 문구는 어댑터가 넘긴 message
 */
@Slf4j
final class RemoteCalls {

    private RemoteCalls() {
    }

    static BusinessException rejected(String target, String action, String message, HttpClientErrorException e) {
        log.warn("{} {} 거절 - status={}, body={}", target, action, e.getStatusCode(), e.getResponseBodyAsString());
        return new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, message);
    }

    /** 서킷 폴백 — 이미 변환된 예외(BusinessException)는 그대로, 그 밖에는 502 */
    static BusinessException unavailable(String target, String action, String message, Throwable t) {
        if (t instanceof BusinessException business) {
            return business;
        }
        if (t instanceof CallNotPermittedException) {
            log.warn("{} {} 차단(서킷 OPEN)", target, action);   // 원인 스택은 OPEN 직전 실패 로그에 있다
        } else {
            log.error("{} {} 실패", target, action, t);           // 연결 실패 · 타임아웃 · 5xx — 스택트레이스 포함 (컨벤션 2-8)
        }
        return new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, message);
    }
}
