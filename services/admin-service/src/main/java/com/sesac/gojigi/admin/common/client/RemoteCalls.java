package com.sesac.gojigi.admin.common.client;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.common.web.exception.ErrorCode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * admin 은 조합 계층이라 다른 서비스의 오류를 그대로 전달한다 (관리자 화면이 같은 code 로 분기):
 * - 4xx 거절: 응답 봉투의 HTTP 상태 · code · message 그대로 — 서킷 실패로 세지 않음(BusinessException)
 * - 연결 실패 · 타임아웃 · 5xx · 서킷 OPEN: 폴백에서 502 EXTERNAL_API_ERROR
 */
@Slf4j
public final class RemoteCalls {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RemoteCalls() {
    }

    /** 대상 서비스가 준 오류 — code · message 를 못 읽으면(봉투가 아님) 502 */
    public static BusinessException relay(String target, HttpClientErrorException e) {
        try {
            JsonNode body = JSON.readTree(e.getResponseBodyAsString());
            String code = body.path("code").asString(null);
            if (code != null) {
                String message = body.path("message").asString("");
                return new BusinessException(new RelayedErrorCode(HttpStatus.valueOf(e.getStatusCode().value()), code, message), message);
            }
        } catch (RuntimeException ignored) {
            // 봉투가 아닌 본문 — 아래에서 502
        }
        log.warn("{} 거절 - status={}, body={}", target, e.getStatusCode(), e.getResponseBodyAsString());
        return new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, target + " 요청이 거절되었습니다.");
    }

    /** 서킷 폴백 — 이미 바꾼 4xx(BusinessException)는 그대로, 그 밖에는 502 */
    public static BusinessException unavailable(String target, Throwable t) {
        if (t instanceof BusinessException business) {
            return business;
        }
        if (t instanceof CallNotPermittedException) {
            log.warn("{} 호출 차단(서킷 OPEN)", target);
        } else {
            log.error("{} 호출 실패", target, t);   // 연결 실패 · 타임아웃 · 5xx — 스택트레이스 포함 (컨벤션 2-8)
        }
        return new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, target + " 서비스를 잠시 사용할 수 없습니다.");
    }

    /** 다른 서비스의 오류 코드를 그대로 싣는 ErrorCode — enum 에 없는 code 를 GlobalExceptionHandler 로 보내기 위함 */
    record RelayedErrorCode(HttpStatus status, String code, String message) implements ErrorCode {

        @Override
        public HttpStatus getStatus() {
            return status;
        }

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public String getMessage() {
            return message;
        }
    }
}
