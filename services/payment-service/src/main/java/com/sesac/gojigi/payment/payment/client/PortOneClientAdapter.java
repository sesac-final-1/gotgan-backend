package com.sesac.gojigi.payment.payment.client;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.payment.payment.client.dto.PortOneCancelRequest;
import com.sesac.gojigi.payment.payment.client.dto.PortOneCancelResponse;
import com.sesac.gojigi.payment.payment.client.dto.PortOnePayment;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * 포트원 호출 규칙 (가이드라인 06장 · docs/api/payment.md):
 * - 서킷 브레이커 "portone" — 연결 실패 · 타임아웃 · 5xx 만 장애로 센다. OPEN 이면 바로 EXTERNAL_API_ERROR(502)
 * - 재시도는 조회(GET)만 1회. 취소(POST)는 재시도하지 않는다 (이중 환불 위험)
 * - 포트원의 4xx 거절은 PortOneRejectedException(EXTERNAL_API_ERROR + 포트원 오류 type)으로 바꿔 던지고 서킷 실패로 세지 않는다
 * - 취소 결과를 모를 때(타임아웃 · 5xx · 일부 409)의 대조는 PaymentCancelExecutor 가 한다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortOneClientAdapter {

    private static final String CIRCUIT = "portone";
    private static final Pattern ERROR_TYPE = Pattern.compile("\"type\"\\s*:\\s*\"([A-Z_]+)\"");

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

    private PortOneRejectedException rejected(String action, String portonePaymentId, HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        String type = errorType(body);
        log.warn("포트원 {} 거절 - paymentId={}, status={}, type={}, body={}", action, portonePaymentId, e.getStatusCode(),
                type, body);
        return new PortOneRejectedException("포트원 " + action + " 요청이 거절되었습니다.", type);
    }

    /** 포트원 V2 오류 본문 { "type": "PAYMENT_ALREADY_CANCELLED", "message": ... } 의 type — 없으면 null */
    static String errorType(String body) {
        if (body == null) {
            return null;
        }
        Matcher m = ERROR_TYPE.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private BusinessException unavailable(String action, String portonePaymentId, Throwable t) {
        if (t instanceof BusinessException business) {
            return business;   // 4xx 거절 — 이미 변환됨
        }
        log.error("포트원 {} 실패 - paymentId={}, cause={}", action, portonePaymentId, t.toString());
        return new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "포트원 " + action + "에 실패했습니다.");
    }
}
