package com.sesac.gojigi.order.order.client;

import com.sesac.gojigi.order.order.client.dto.PaymentCancelRequest;
import com.sesac.gojigi.order.order.client.dto.PaymentPrepareRequest;
import com.sesac.gojigi.order.order.client.dto.PaymentResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
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

    /** 결제 취소 (Saga 보상은 전액) — 되돌릴 게 없으면 payment 가 아무 일도 안 하고 200 */
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "cancelFallback")
    public void cancel(String orderNo, PaymentCancelRequest request) {
        try {
            paymentClient.cancel(orderNo, request);
        } catch (HttpClientErrorException e) {
            throw RemoteCalls.rejected(TARGET, "결제 취소", e);
        }
    }

    PaymentResponse prepareFallback(PaymentPrepareRequest request, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, "결제 준비", t);
    }

    void cancelFallback(String orderNo, PaymentCancelRequest request, Throwable t) {
        throw RemoteCalls.unavailable(TARGET, "결제 취소", t);
    }
}
