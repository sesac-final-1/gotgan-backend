package com.sesac.gojigi.payment.payment.client;

import com.sesac.gojigi.payment.payment.client.dto.PortOneCancelRequest;
import com.sesac.gojigi.payment.payment.client.dto.PortOneCancelResponse;
import com.sesac.gojigi.payment.payment.client.dto.PortOnePayment;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * 포트원 V2 REST API (https://api.portone.io, 인증 헤더 Authorization: PortOne {API Secret}).
 * 직접 쓰지 않고 PortOneClientAdapter(서킷 브레이커 "portone" · GET 재시도)를 거친다.
 */
@HttpExchange("/payments")
public interface PortOneClient {

    /** 결제 단건 조회 — 결제창을 연 적 없는 paymentId 는 404 PAYMENT_NOT_FOUND */
    @GetExchange("/{paymentId}")
    PortOnePayment getPayment(@PathVariable String paymentId);

    /** 결제 취소 — amount 를 비우면 전액, 넣으면 부분 취소 */
    @PostExchange("/{paymentId}/cancel")
    PortOneCancelResponse cancel(@PathVariable String paymentId, @RequestBody PortOneCancelRequest body);
}
