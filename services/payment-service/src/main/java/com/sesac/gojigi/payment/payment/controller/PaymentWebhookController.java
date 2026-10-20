package com.sesac.gojigi.payment.payment.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.payment.payment.client.PortOneWebhookVerifier;
import com.sesac.gojigi.payment.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * 포트원 V2 웹훅 (게이트웨이 permitAll — 토큰 없음, 대신 서명 검증). 서명은 원문 본문으로 계산하므로 String 으로 받는다.
 * 본문은 "확인 요청"으로만 쓰고 결과는 포트원을 다시 조회해 정한다. 처리 중 오류는 5xx → 포트원이 재전송(최대 5회).
 */
@Slf4j
@Tag(name = "결제 웹훅", description = "포트원 → payment-service — 인가: 공개(서명 검증)")
@RestController
@RequestMapping("/api/payments/webhook")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final PortOneWebhookVerifier verifier;
    private final PaymentService paymentService;
    private final JsonMapper jsonMapper;

    record PortOneWebhook(String type, Data data) {
        record Data(String paymentId) {
        }
    }

    @Operation(summary = "포트원 웹훅 수신", description = "서명 실패 400 PAYMENT_WEBHOOK_INVALID_SIGNATURE. 모르는 type · paymentId 는 200 무시")
    @PostMapping
    public ApiResponse<Void> receive(
            @RequestHeader(name = "webhook-id", required = false) String webhookId,
            @RequestHeader(name = "webhook-timestamp", required = false) String webhookTimestamp,
            @RequestHeader(name = "webhook-signature", required = false) String webhookSignature,
            @RequestBody String body) {
        verifier.verify(webhookId, webhookTimestamp, webhookSignature, body);
        PortOneWebhook webhook = jsonMapper.readValue(body, PortOneWebhook.class);
        if (webhook.type() != null && webhook.type().startsWith("Transaction.") && webhook.data() != null
                && webhook.data().paymentId() != null) {
            log.info("포트원 웹훅 - type={}, paymentId={}", webhook.type(), webhook.data().paymentId());
            paymentService.reconcile(webhook.data().paymentId());
        }
        return ApiResponse.success("웹훅을 처리했습니다.");
    }
}
