package com.sesac.gojigi.store.store.client;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.HttpClientErrorException;

/**
 * 다른 서비스 호출 실패를 store 예외로 바꾸는 공통 규칙 (order-service RemoteCalls 와 같은 규칙):
 * - 4xx: 호출 쪽(store) 버그라 warn + 502 — 서킷 실패로 세지 않음(BusinessException)
 * - 연결 실패 · 타임아웃 · 5xx · 서킷 OPEN: 폴백에서 502 — 폐점은 확인할 수 없으면 거절(STR-13)
 */
@Slf4j
final class RemoteCalls {

    private RemoteCalls() {
    }

    static BusinessException rejected(String target, String action, HttpClientErrorException e) {
        log.warn("{} {} 거절 - status={}, body={}", target, action, e.getStatusCode(), e.getResponseBodyAsString());
        return new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, target + " " + action + " 요청이 거절되었습니다.");
    }

    /** 서킷 폴백 — 이미 변환된 예외(BusinessException)는 그대로, 그 밖에는 502 */
    static BusinessException unavailable(String target, String action, Throwable t) {
        if (t instanceof BusinessException business) {
            return business;
        }
        if (t instanceof CallNotPermittedException) {
            log.warn("{} {} 차단(서킷 OPEN)", target, action);
        } else {
            log.error("{} {} 실패", target, action, t);   // 연결 실패 · 타임아웃 · 5xx — 스택트레이스 포함 (컨벤션 2-8)
        }
        return new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR,
                target + " " + action + "에 실패했습니다. 잠시 후 다시 시도해 주세요.");
    }
}
