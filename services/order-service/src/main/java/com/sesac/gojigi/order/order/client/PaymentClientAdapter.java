package com.sesac.gojigi.order.order.client;

import com.sesac.gojigi.order.order.client.dto.PaymentCancelRequest;
import com.sesac.gojigi.order.order.client.dto.PaymentPrepareRequest;
import com.sesac.gojigi.order.order.client.dto.PaymentResponse;
import com.sesac.gojigi.order.order.client.dto.PaymentVerifyRequest;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.exception.OrderException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * payment-service 호출 — 서킷 브레이커 "payment", 재시도 없음(모두 POST — Saga 가 sagaId · orderNo 로 다시 부른다).
 * 취소의 409 PAYMENT_CANCEL_PENDING 은 "결과 모름" — 여기서는 EXTERNAL_API_ERROR 로 올리고, Saga 가 보상 미완료로 남겨 재시도한다
 * (docs/api/payment.md 3 · 8절). 실패 변환 규칙은 RemoteCalls.
 */
@Component
@RequiredArgsConstructor
public class PaymentClientAdapter {

    private static final String CIRCUIT = "payment";
    private static final String TARGET = "payment";

    private final PaymentClient paymentClient;

    /** 결제 준비 (Saga PAYMENT_READY) — 응답의 portonePaymentId 가 브라우저 결제창 paymentId */
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "prepareFallback")
    public PaymentResponse prepare(PaymentPrepareRequest request) {
        try {
            return paymentClient.prepare(request).data();
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.rejected(TARGET, "결제 준비", e);
        }
    }

    /**
     * 결제 검증 (Saga PAYMENT_VERIFIED) — payment 가 포트원에 직접 조회해 상태 · 금액(= order 의 payAmt)을 비교 (payment.md 2절).
     * 미결제(결제창 진행 중 · 닫음)는 ORDER_PAYMENT_NOT_COMPLETED — 결제 대기 유지.
     * 금액 불일치(payment 가 이미 자동 환불) · 끝난 결제 · 결제 없음은 ORDER_PAYMENT_FAILED — 보상 대상.
     */
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "verifyFallback")
    public PaymentResponse verify(PaymentVerifyRequest request) {
        try {
            return paymentClient.verify(request).data();
        } catch (HttpClientErrorException e) {
            String code = RemoteCalls.errorCode(e);
            if ("PAYMENT_NOT_COMPLETED".equals(code)) {
                throw new OrderException(OrderErrorCode.ORDER_PAYMENT_NOT_COMPLETED);
            }
            if ("PAYMENT_AMOUNT_MISMATCH".equals(code) || "PAYMENT_INVALID_STATUS".equals(code)
                    || e instanceof HttpClientErrorException.NotFound) {
                throw new OrderException(OrderErrorCode.ORDER_PAYMENT_FAILED);
            }
            throw RemoteCalls.rejected(TARGET, "결제 검증", e);
        }
    }

    /** 결제 취소 (Saga 보상은 전액) — 되돌릴 게 없으면 payment 가 아무 일도 안 하고 200 */
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "cancelFallback")
    public void cancel(String orderNo, PaymentCancelRequest request) {
        try {
            paymentClient.cancel(orderNo, request);
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.rejected(TARGET, "결제 취소", e);
        }
    }

    /**
     * 결제 상태 조회 (주문 상세의 결제수단) — 결제가 없으면(결제 준비 전에 끝난 주문) null.
     * 조회는 보조 정보라 장애여도 예외 대신 null — 주문 상세는 결제수단만 비우고 그대로 보여 준다.
     */
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "findFallback")
    public PaymentResponse find(String orderNo) {
        try {
            return paymentClient.find(orderNo).data();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return null;
            }
            throw RemoteCalls.rejected(TARGET, "결제 조회", e);
        }
    }

    PaymentResponse findFallback(String orderNo, Throwable t) {
        RemoteCalls.unavailable(TARGET, "결제 조회", t);   // 로그만 남기고 결제수단 없이
        return null;
    }

    PaymentResponse prepareFallback(PaymentPrepareRequest request, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, "결제 준비", t);
    }

    PaymentResponse verifyFallback(PaymentVerifyRequest request, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, "결제 검증", t);   // 결과 모름 — 결제 대기 유지
    }

    void cancelFallback(String orderNo, PaymentCancelRequest request, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, "결제 취소", t);
    }
}
