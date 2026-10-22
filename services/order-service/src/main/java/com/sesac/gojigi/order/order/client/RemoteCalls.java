package com.sesac.gojigi.order.order.client;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.HttpClientErrorException;

/**
 * 다른 서비스 호출 실패를 order 예외로 바꾸는 공통 규칙 (어댑터 3개가 함께 쓴다):
 * - 4xx 거절: 응답 봉투의 code 를 보고 어댑터가 도메인 예외로 바꾸고, 모르는 코드는 EXTERNAL_API_ERROR — 서킷 실패로 세지 않음
 * - 연결 실패 · 타임아웃 · 5xx · 서킷 OPEN: 폴백에서 EXTERNAL_API_ERROR(502) — 결과를 모를 수 있으므로 Saga 는 보상을 시도
 */
@Slf4j
final class RemoteCalls {

    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*\"([A-Z_]+)\"");

    private RemoteCalls() {
    }

    /** 응답 봉투 { "code": "STOCK_INSUFFICIENT", ... } 의 code — 없으면 null */
    static String errorCode(HttpClientErrorException e) {
        Matcher m = CODE.matcher(e.getResponseBodyAsString());
        return m.find() ? m.group(1) : null;
    }

    /** 어댑터가 따로 처리하지 않는 4xx — 호출 쪽(order) 버그일 가능성이 높아 warn 으로 본문까지 남긴다 */
    static BusinessException rejected(String target, String action, HttpClientErrorException e) {
        log.warn("{} {} 거절 - status={}, body={}", target, action, e.getStatusCode(), e.getResponseBodyAsString());
        return new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, target + " " + action + " 요청이 거절되었습니다.");
    }

    /** 서킷 폴백 — 이미 변환된 4xx(BusinessException)는 그대로, 그 밖에는 502 */
    static BusinessException unavailable(String target, String action, Throwable t) {
        if (t instanceof BusinessException business) {
            return business;
        }
        if (t instanceof CallNotPermittedException) {
            log.warn("{} {} 차단(서킷 OPEN)", target, action);   // 원인 스택은 OPEN 직전 실패 로그에 있다
        } else {
            log.error("{} {} 실패", target, action, t);           // 연결 실패 · 타임아웃 · 5xx — 스택트레이스 포함 (컨벤션 2-8)
        }
        return new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, target + " " + action + "에 실패했습니다.");
    }
}
