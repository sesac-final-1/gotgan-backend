package com.sesac.gojigi.payment.payment.client;

import com.sesac.gojigi.payment.common.exception.BusinessException;
import com.sesac.gojigi.payment.common.exception.CommonErrorCode;
import com.sesac.gojigi.payment.payment.client.dto.PortOneCancelRequest;
import com.sesac.gojigi.payment.payment.client.dto.PortOneCancelResponse;
import com.sesac.gojigi.payment.payment.client.dto.PortOnePayment;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * 포트원 호출 규칙 (가이드라인 06장 · docs/api/payment.md):
 * - 서킷 브레이커 "portone" — 연결 실패 · 타임아웃 · 5xx 만 장애로 센다. OPEN 이면 바로 EXTERNAL_API_ERROR(502)
 * - 재시도는 조회(GET)만 1회. 취소(POST)는 재시도하지 않는다 (이중 환불 위험)
 * - 포트원의 4xx 거절은 EXTERNAL_API_ERROR 로 바꿔 던지고 서킷 실패로 세지 않는다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortOneClientAdapter {

    private static final String CIRCUIT = "portone";

    private final PortOneClient portOneClient;

    /** 결제 조회 — 포트원에 결제가 없으면(결제창을 연 적 없음) empty */
    @Retry(name = CIRCUIT)
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "getPaymentFallback")
    public Optional<PortOnePayment> getPayment(String portonePaymentId) {
        try {
            return Optional.of(portOneClient.getPayment(portonePaymentId));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (HttpClientErrorException e) {
            throw rejected("조회", portonePaymentId, e);
        }
    }

    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "cancelFallback")
    public PortOneCancelResponse cancel(String portonePaymentId, PortOneCancelRequest request) {
        try {
            return portOneClient.cancel(portonePaymentId, request);
        } catch (HttpClientErrorException e) {
            throw rejected("취소", portonePaymentId, e);
        }
    }

    private Optional<PortOnePayment> getPaymentFallback(String portonePaymentId, Throwable t) {
        throw unavailable("조회", portonePaymentId, t);
    }

    private PortOneCancelResponse cancelFallback(String portonePaymentId, PortOneCancelRequest request, Throwable t) {
        throw unavailable("취소", portonePaymentId, t);
    }

    private BusinessException rejected(String action, String portonePaymentId, HttpClientErrorException e) {
        log.warn("포트원 {} 거절 - paymentId={}, status={}, body={}", action, portonePaymentId, e.getStatusCode(),
                e.getResponseBodyAsString());
        return new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "포트원 " + action + " 요청이 거절되었습니다.");
    }

    private BusinessException unavailable(String action, String portonePaymentId, Throwable t) {
        if (t instanceof BusinessException business) {
            return business;   // 4xx 거절 — 이미 변환됨
        }
        log.error("포트원 {} 실패 - paymentId={}, cause={}", action, portonePaymentId, t.toString());
        return new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "포트원 " + action + "에 실패했습니다.");
    }
}
