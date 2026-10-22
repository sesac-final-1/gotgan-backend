package com.sesac.gojigi.payment.payment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.payment.TestcontainersConfiguration;
import com.sesac.gojigi.payment.common.logging.PaymentLogContext;
import com.sesac.gojigi.payment.payment.client.PortOneClient;
import com.sesac.gojigi.payment.payment.client.PortOneWebhookVerifierTestSupport;
import com.sesac.gojigi.payment.payment.client.dto.PortOneCancelRequest;
import com.sesac.gojigi.payment.payment.client.dto.PortOneCancelResponse;
import com.sesac.gojigi.payment.payment.client.dto.PortOnePayment;
import com.sesac.gojigi.payment.payment.entity.Payment;
import com.sesac.gojigi.payment.payment.entity.PaymentCancel;
import com.sesac.gojigi.payment.payment.entity.PaymentStatus;
import com.sesac.gojigi.payment.payment.repository.PaymentCancelRepository;
import com.sesac.gojigi.payment.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/**
 * 내부 API 4개 + 웹훅을 HTTP 로 호출해 계약(docs/api/payment.md)대로 응답하는지 — 실제 MySQL + 가짜 포트원(PortOneClient mock).
 * 어댑터 · 서킷 브레이커는 실제 빈. 테스트마다 다른 orderNo 를 써서 서로 영향이 없게 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class PaymentApiIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final long AMOUNT = 64_400L;

    @Autowired MockMvc mockMvc;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentCancelRepository paymentCancelRepository;
    @Autowired MeterRegistry meterRegistry;
    @MockitoBean PortOneClient portOneClient;

    // ── 도우미

    private static String newOrderNo() {
        return String.format("20261021-%06d", SEQ.incrementAndGet());
    }

    private static PortOnePayment portOne(String status, long total, long cancelled) {
        return new PortOnePayment(status, "pay-x", new PortOnePayment.Amount(total, "PAID".equals(status) ? total : 0, cancelled),
                new PortOnePayment.Method("PaymentMethodCard"), "pg-tx-1", OffsetDateTime.parse("2026-10-21T00:12:33Z"));
    }

    private static PortOneCancelResponse cancelled(long amount) {
        return new PortOneCancelResponse(new PortOneCancelResponse.Cancellation("SUCCEEDED", "cancel-" + UUID.randomUUID(),
                amount, OffsetDateTime.parse("2026-10-22T01:00:00Z")));
    }

    private static HttpClientErrorException portOneNotFound() {
        return HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY,
                "{\"type\":\"PAYMENT_NOT_FOUND\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private ResultActions prepare(String orderNo, long amount) throws Exception {
        return mockMvc.perform(post("/internal/payments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":1,\"orderNo\":\"" + orderNo + "\",\"amount\":" + amount + ",\"sagaId\":\"saga-" + orderNo + "\"}"));
    }

    private ResultActions verifyPayment(String orderNo, long amount) throws Exception {
        return mockMvc.perform(post("/internal/payments/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sagaId\":\"saga-" + orderNo + "\",\"orderNo\":\"" + orderNo + "\",\"amount\":" + amount + "}"));
    }

    private ResultActions cancel(String orderNo, String sagaId, Long amount) throws Exception {
        String amountJson = amount == null ? "" : ",\"orderItemId\":10,\"amount\":" + amount;
        return mockMvc.perform(post("/internal/payments/" + orderNo + "/cancel").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sagaId\":\"" + sagaId + "\",\"reason\":\"구매자 취소\"" + amountJson + "}"));
    }

    /** READY → 검증으로 PAID 까지 */
    private String paidOrder() throws Exception {
        String orderNo = newOrderNo();
        prepare(orderNo, AMOUNT);
        when(portOneClient.getPayment("pay-" + orderNo)).thenReturn(portOne("PAID", AMOUNT, 0));
        verifyPayment(orderNo, AMOUNT).andExpect(status().isOk());
        return orderNo;
    }

    private Payment payment(String orderNo) {
        return paymentRepository.findByOrderNo(orderNo).orElseThrow();
    }

    private List<PaymentCancel> cancels(String orderNo) {
        Long id = payment(orderNo).getId();
        return paymentCancelRepository.findAll().stream().filter(c -> c.getPayment().getId().equals(id)).toList();
    }

    // ── 1. 결제 준비

    @Nested
    class Prepare {

        @Test
        @DisplayName("처음은 201 READY · pay-{orderNo}, 같은 금액 재요청은 200 같은 결제, 다른 금액은 409")
        void prepare_idempotentByOrderNo() throws Exception {
            String orderNo = newOrderNo();

            prepare(orderNo, AMOUNT).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.status").value("READY"))
                    .andExpect(jsonPath("$.data.portonePaymentId").value("pay-" + orderNo));
            prepare(orderNo, AMOUNT).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.portonePaymentId").value("pay-" + orderNo));
            prepare(orderNo, AMOUNT + 1).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_CONFLICT"));
            assertThat(paymentRepository.findAll().stream().filter(p -> p.getOrderNo().equals(orderNo))).hasSize(1);
        }

        @Test
        @DisplayName("0원은 400 INVALID_INPUT — 0원 주문은 order 가 결제 단계를 건너뜀")
        void prepare_zeroAmount_rejected() throws Exception {
            prepare(newOrderNo(), 0).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
    }

    // ── 2. 결제 검증

    @Nested
    class Verify {

        @Test
        @DisplayName("포트원 PAID + 금액 일치면 PAID · 결제수단 · 결제 시각(KST) 기록, 재요청은 포트원을 다시 부르지 않음")
        void verify_paid() throws Exception {
            String orderNo = newOrderNo();
            prepare(orderNo, AMOUNT);
            when(portOneClient.getPayment("pay-" + orderNo)).thenReturn(portOne("PAID", AMOUNT, 0));

            verifyPayment(orderNo, AMOUNT).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PAID"))
                    .andExpect(jsonPath("$.data.method").value("CARD"))
                    .andExpect(jsonPath("$.data.paidAt").value("2026-10-21T09:12:33+09:00"));
            verifyPayment(orderNo, AMOUNT).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PAID"));

            verify(portOneClient, times(1)).getPayment("pay-" + orderNo);
            assertThat(payment(orderNo).getPgTxId()).isEqualTo("pg-tx-1");
        }

        @Test
        @DisplayName("포트원 미결제 · 결제창을 연 적 없음 → 409 PAYMENT_NOT_COMPLETED, 상태는 READY 그대로")
        void verify_notCompleted_keepsReady() throws Exception {
            String orderNo = newOrderNo();
            prepare(orderNo, AMOUNT);
            when(portOneClient.getPayment("pay-" + orderNo)).thenReturn(portOne("READY", AMOUNT, 0)).thenThrow(portOneNotFound());

            verifyPayment(orderNo, AMOUNT).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_NOT_COMPLETED"));
            verifyPayment(orderNo, AMOUNT).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_NOT_COMPLETED"));
            assertThat(payment(orderNo).getStatus()).isEqualTo(PaymentStatus.READY);
        }

        @Test
        @DisplayName("금액 불일치 → 포트원 전액 자동 취소 · 취소 기록 · FAILED · 409 PAYMENT_AMOUNT_MISMATCH · 지표 증가")
        void verify_amountMismatch_autoCancels() throws Exception {
            String orderNo = newOrderNo();
            prepare(orderNo, AMOUNT);
            when(portOneClient.getPayment("pay-" + orderNo)).thenReturn(portOne("PAID", 1_000L, 0));
            when(portOneClient.cancel(eq("pay-" + orderNo), any())).thenReturn(cancelled(1_000L));
            double before = meterRegistry.counter("payment.mismatch").count();

            verifyPayment(orderNo, AMOUNT).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_AMOUNT_MISMATCH"));

            ArgumentCaptor<PortOneCancelRequest> sent = ArgumentCaptor.forClass(PortOneCancelRequest.class);
            verify(portOneClient).cancel(eq("pay-" + orderNo), sent.capture());
            assertThat(sent.getValue().amount()).isEqualTo(1_000L);              // 전액도 금액 명시 (포트원 잔액)
            assertThat(sent.getValue().currentCancellableAmount()).isEqualTo(1_000L);
            assertThat(payment(orderNo).getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(cancels(orderNo)).singleElement()
                    .satisfies(c -> assertThat(c.getReason()).isEqualTo("결제 금액 불일치 자동 취소"));
            assertThat(meterRegistry.counter("payment.mismatch").count()).isEqualTo(before + 1);
        }

        @Test
        @DisplayName("포트원 장애(5xx) → 502 EXTERNAL_API_ERROR, 상태는 READY 그대로 (order 는 결제 대기 유지)")
        void verify_portOneDown_502() throws Exception {
            String orderNo = newOrderNo();
            prepare(orderNo, AMOUNT);
            when(portOneClient.getPayment("pay-" + orderNo))
                    .thenThrow(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "down", HttpHeaders.EMPTY, null, null));

            verifyPayment(orderNo, AMOUNT).andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("EXTERNAL_API_ERROR"));
            assertThat(payment(orderNo).getStatus()).isEqualTo(PaymentStatus.READY);
        }

        @Test
        @DisplayName("결제가 없으면 404 PAYMENT_NOT_FOUND")
        void verify_unknownOrder_404() throws Exception {
            verifyPayment(newOrderNo(), AMOUNT).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
        }

        @Test
        @DisplayName("예외 처리기 로그에도 orderNo · sagaId 가 MDC 로 붙고, 요청이 끝나면 지워진다 (OBS-02)")
        void verify_exceptionLog_hasBusinessKeys() throws Exception {
            Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
            ListAppender<ILoggingEvent> logs = new ListAppender<>();
            logs.start();
            handlerLogger.addAppender(logs);
            try {
                String orderNo = newOrderNo();
                verifyPayment(orderNo, AMOUNT).andExpect(status().isNotFound());

                assertThat(logs.list).anySatisfy(event -> {
                    assertThat(event.getMDCPropertyMap()).containsEntry(PaymentLogContext.ORDER_NO, orderNo);
                    assertThat(event.getMDCPropertyMap()).containsKey(PaymentLogContext.SAGA_ID);
                });
                assertThat(MDC.get(PaymentLogContext.ORDER_NO)).isNull();   // 필터가 정리 — 다음 요청에 남지 않음
            } finally {
                handlerLogger.detachAppender(logs);
            }
        }
    }

    // ── 3. 결제 취소

    @Nested
    class Cancel {

        @Test
        @DisplayName("부분 취소 두 번 → PARTIAL_CANCELLED → CANCELLED, 같은 sagaId 재요청은 포트원을 다시 부르지 않음")
        void cancel_partialAccumulates_andSagaIdempotent() throws Exception {
            String orderNo = paidOrder();
            when(portOneClient.cancel(eq("pay-" + orderNo), any())).thenReturn(cancelled(32_200L));

            cancel(orderNo, "claim-saga-1", 32_200L).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.payment.status").value("PARTIAL_CANCELLED"))
                    .andExpect(jsonPath("$.data.cancel.cancelAmount").value(32_200));
            cancel(orderNo, "claim-saga-1", 32_200L).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.payment.status").value("PARTIAL_CANCELLED"));
            cancel(orderNo, "claim-saga-2", 32_200L).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.payment.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.data.payment.cancelledAmount").value(AMOUNT));

            verify(portOneClient, times(2)).cancel(eq("pay-" + orderNo), any());
            assertThat(cancels(orderNo)).hasSize(2);
        }

        @Test
        @DisplayName("남은 금액보다 큰 부분 취소는 409, 결제 전(READY) 부분 취소도 409")
        void cancel_partial_rejected() throws Exception {
            String orderNo = paidOrder();
            cancel(orderNo, "claim-saga-x", AMOUNT + 1).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_CANCEL_AMOUNT_EXCEEDED"));

            String ready = newOrderNo();
            prepare(ready, AMOUNT);
            cancel(ready, "claim-saga-y", 1_000L).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_INVALID_STATUS"));
            verify(portOneClient, never()).cancel(any(), any());
        }

        @Test
        @DisplayName("주문 Saga 보상(전액): 결제 없음 → data null / READY · 포트원 결제 없음 → FAILED, 포트원 취소 안 함")
        void cancelAll_nothingToRefund() throws Exception {
            cancel(newOrderNo(), "order-saga-1", null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").doesNotExist());

            String orderNo = newOrderNo();
            prepare(orderNo, AMOUNT);
            when(portOneClient.getPayment("pay-" + orderNo)).thenThrow(portOneNotFound());
            cancel(orderNo, "order-saga-2", null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.payment.status").value("FAILED"))
                    .andExpect(jsonPath("$.data.cancel").doesNotExist());
            verify(portOneClient, never()).cancel(any(), any());
        }

        @Test
        @DisplayName("주문 Saga 보상(전액): READY 인데 포트원에 결제가 있으면(타임아웃 등) 포트원 취소 후 FAILED · sagaId 기록")
        void cancelAll_readyButPaidAtPortOne_refunds() throws Exception {
            String orderNo = newOrderNo();
            prepare(orderNo, AMOUNT);
            when(portOneClient.getPayment("pay-" + orderNo)).thenReturn(portOne("PAID", AMOUNT, 0));
            when(portOneClient.cancel(eq("pay-" + orderNo), any())).thenReturn(cancelled(AMOUNT));

            cancel(orderNo, "order-saga-3", null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.payment.status").value("FAILED"))
                    .andExpect(jsonPath("$.data.cancel.sagaId").value("order-saga-3"));
            cancel(orderNo, "order-saga-3", null).andExpect(status().isOk());   // 재시도 — 포트원 재호출 없음
            verify(portOneClient, times(1)).cancel(eq("pay-" + orderNo), any());
        }

        @Test
        @DisplayName("주문 Saga 보상(전액): PAID 면 남은 금액 전액 취소 → CANCELLED")
        void cancelAll_paid_cancelsRemaining() throws Exception {
            String orderNo = paidOrder();
            when(portOneClient.cancel(eq("pay-" + orderNo), any())).thenReturn(cancelled(AMOUNT));

            cancel(orderNo, "order-saga-4", null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.payment.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.data.cancel.cancelAmount").value(AMOUNT));
        }
    }

    // ── 4. 상태 조회

    @Test
    @DisplayName("상태 조회는 DB 상태만 — 포트원을 부르지 않음, 없으면 404")
    void get_statusFromDb() throws Exception {
        String orderNo = newOrderNo();
        prepare(orderNo, AMOUNT);

        mockMvc.perform(get("/internal/payments").param("orderNo", orderNo)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"));
        mockMvc.perform(get("/internal/payments").param("orderNo", newOrderNo())).andExpect(status().isNotFound());
        verify(portOneClient, never()).getPayment("pay-" + orderNo);
    }

    @Test
    @DisplayName("orderNo 누락은 400 INVALID_INPUT · 없는 경로는 404 NOT_FOUND (500 아님)")
    void commonErrors_notGlobalError() throws Exception {
        mockMvc.perform(get("/internal/payments")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mockMvc.perform(get("/internal/no-such-path")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ── 5. 웹훅

    @Nested
    class Webhook {

        private ResultActions send(String body, boolean validSignature) throws Exception {
            long now = Instant.now().getEpochSecond();
            String signature = validSignature ? PortOneWebhookVerifierTestSupport.sign("msg-1", now, body) : "v1,AAAA";
            return mockMvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                    .header("webhook-id", "msg-1").header("webhook-timestamp", String.valueOf(now))
                    .header("webhook-signature", signature).content(body));
        }

        private String paidWebhook(String orderNo) {
            return "{\"type\":\"Transaction.Paid\",\"timestamp\":\"2026-10-21T00:12:33Z\","
                    + "\"data\":{\"paymentId\":\"pay-" + orderNo + "\",\"storeId\":\"store-1\",\"transactionId\":\"tx-1\"}}";
        }

        @Test
        @DisplayName("서명이 틀리면 400 PAYMENT_WEBHOOK_INVALID_SIGNATURE · 처리 안 함")
        void webhook_invalidSignature_400() throws Exception {
            String orderNo = newOrderNo();
            prepare(orderNo, AMOUNT);

            send(paidWebhook(orderNo), false).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PAYMENT_WEBHOOK_INVALID_SIGNATURE"));
            verify(portOneClient, never()).getPayment(any());
        }

        @Test
        @DisplayName("Transaction.Paid → 포트원 재조회로 PAID (브라우저가 돌아오지 않은 경우)")
        void webhook_paid_marksPaid() throws Exception {
            String orderNo = newOrderNo();
            prepare(orderNo, AMOUNT);
            when(portOneClient.getPayment("pay-" + orderNo)).thenReturn(portOne("PAID", AMOUNT, 0));

            send(paidWebhook(orderNo), true).andExpect(status().isOk());

            assertThat(payment(orderNo).getStatus()).isEqualTo(PaymentStatus.PAID);
        }

        @Test
        @DisplayName("결제 실패 처리 후 늦게 승인된 결제 → 포트원 자동 취소 (FAILED 유지)")
        void webhook_latePaymentAfterFailed_refunds() throws Exception {
            String orderNo = newOrderNo();
            prepare(orderNo, AMOUNT);
            when(portOneClient.getPayment("pay-" + orderNo)).thenThrow(portOneNotFound())
                    .thenReturn(portOne("PAID", AMOUNT, 0));
            cancel(orderNo, "order-saga-5", null).andExpect(jsonPath("$.data.payment.status").value("FAILED"));
            when(portOneClient.cancel(eq("pay-" + orderNo), any())).thenReturn(cancelled(AMOUNT));

            send(paidWebhook(orderNo), true).andExpect(status().isOk());

            verify(portOneClient, times(1)).cancel(eq("pay-" + orderNo), any());
            assertThat(payment(orderNo).getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(cancels(orderNo)).singleElement()
                    .satisfies(c -> assertThat(c.getReason()).isEqualTo("결제 실패 처리 후 승인된 결제 자동 취소"));
        }

        @Test
        @DisplayName("모르는 type · 결제와 무관한 웹훅은 200 무시")
        void webhook_unknownType_ignored() throws Exception {
            send("{\"type\":\"BillingKey.Issued\",\"data\":{\"billingKey\":\"bk-1\"}}", true).andExpect(status().isOk());
            send("{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-unknown\"}}", true).andExpect(status().isOk());
            verify(portOneClient, never()).getPayment("pay-unknown");
        }
    }
}
