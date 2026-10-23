package com.sesac.gojigi.order.order.client;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.order.order.client.dto.PaymentCancelRequest;
import com.sesac.gojigi.order.order.client.dto.PaymentCancelResponse;
import com.sesac.gojigi.order.order.client.dto.PaymentPrepareRequest;
import com.sesac.gojigi.order.order.client.dto.PaymentResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/** payment-service 내부 API (docs/api/payment.md) — 직접 쓰지 않고 PaymentClientAdapter(서킷 브레이커 "payment")를 거친다 */
@HttpExchange("/internal/payments")
public interface PaymentClient {

    /** 결제 준비 — READY 생성 · portonePaymentId 발급, orderNo 멱등 */
    @PostExchange
    ApiResponse<PaymentResponse> prepare(@RequestBody PaymentPrepareRequest request);

    /** 결제 취소 — 전액(주문 Saga 보상) · 부분, sagaId 멱등 */
    @PostExchange("/{orderNo}/cancel")
    ApiResponse<PaymentCancelResponse> cancel(@PathVariable String orderNo, @RequestBody PaymentCancelRequest request);

    /** 결제 상태 조회 — 포트원을 부르지 않고 DB 상태만, 결제가 없으면 404 PAYMENT_NOT_FOUND (payment.md 4절) */
    @GetExchange
    ApiResponse<PaymentResponse> find(@RequestParam String orderNo);
}
