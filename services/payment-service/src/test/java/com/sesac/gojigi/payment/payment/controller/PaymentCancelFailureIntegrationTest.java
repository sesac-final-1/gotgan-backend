package com.sesac.gojigi.payment.payment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.payment.TestcontainersConfiguration;
import com.sesac.gojigi.payment.payment.client.PortOneClient;
import com.sesac.gojigi.payment.payment.client.dto.PortOneCancelRequest;
import com.sesac.gojigi.payment.payment.client.dto.PortOneCancelResponse;
import com.sesac.gojigi.payment.payment.client.dto.PortOnePayment;
import com.sesac.gojigi.payment.payment.entity.CancelStatus;
import com.sesac.gojigi.payment.payment.entity.Payment;
import com.sesac.gojigi.payment.payment.entity.PaymentCancel;
import com.sesac.gojigi.payment.payment.entity.PaymentStatus;
import com.sesac.gojigi.payment.payment.repository.PaymentCancelRepository;
import com.sesac.gojigi.payment.payment.repository.PaymentRepository;
import com.sesac.gojigi.payment.payment.service.PaymentCancelRecoveryScheduler;
import com.sesac.gojigi.payment.payment.service.PaymentService;
import com.sesac.gojigi.payment.payment.service.PaymentTxService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * 결제 취소 · 환불의 장애 시나리오 — "모든 단계가 실패할 수 있다"를 하나씩 재현한다 (docs/api/payment.md 8절).
 * 포트원 응답 끊김 · 조회 실패 · 처리 중(REQUESTED) · 확정 거절 · 포트원 성공 후 DB 기록 실패 · 기록 밖의 취소.
 * 공통 기대: 돈(포트원)과 기록(payment · payment_cancel)이 어긋난 채 끝나지 않고, 같은 취소가 두 번 나가지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class PaymentCancelFailureIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final long AMOUNT = 64_400L;
    private static final OffsetDateTime AT = OffsetDateTime.parse("2099-01-01T00:00:00Z");   // 선기록 이후 요청된 취소

    @Autowired MockMvc mockMvc;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentCancelRepository paymentCancelRepository;
    @Autowired PaymentService paymentService;
    @Autowired PaymentCancelRecoveryScheduler recoveryScheduler;
    @Autowired CircuitBreakerRegistry circuitBreakerRegistry;
    @MockitoBean PortOneClient portOneClient;
    @MockitoSpyBean PaymentTxService paymentTx;

    @BeforeEach
    void resetCircuit() {
        circuitBreakerRegistry.circuitBreaker("portone").reset();   // 장애를 일부러 내는 테스트끼리 서킷 상태가 번지지 않게
    }

    // ── 도우미

    private static String newOrderNo() {
        return String.format("20261022-%06d", SEQ.incrementAndGet());
    }

    private static PortOnePayment portOne(String status, long total, long cancelled, PortOnePayment.Cancellation... cs) {
        return new PortOnePayment(status, "pay-x", new PortOnePayment.Amount(total, total, cancelled),
                new PortOnePayment.Method("PaymentMethodCard"), "pg-tx-1", OffsetDateTime.parse("2026-10-22T00:12:33Z"),
                List.of(cs));
    }

    private static PortOnePayment.Cancellation portOneCancel(String status, String id, long amount) {
        return new PortOnePayment.Cancellation(status, id, amount, AT, AT);
    }

    private static PortOneCancelResponse cancelResponse(String status, String id, long amount) {
        return new PortOneCancelResponse(new PortOneCancelResponse.Cancellation(status, id, amount, AT));
    }

    private static ResourceAccessException timeout() {
        return new ResourceAccessException("Read timed out");
    }

    private static HttpClientErrorException rejected(HttpStatus status, String type) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY,
                ("{\"type\":\"" + type + "\"}").getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static HttpServerErrorException unavailable() {
        return HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", HttpHeaders.EMPTY,
                new byte[0], StandardCharsets.UTF_8);
    }

    private ResultActions cancel(String orderNo, String sagaId, Long amount) throws Exception {
        String amountJson = amount == null ? "" : ",\"orderItemId\":10,\"amount\":" + amount;
        return mockMvc.perform(post("/internal/payments/" + orderNo + "/cancel").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sagaId\":\"" + sagaId + "\",\"reason\":\"구매자 취소\"" + amountJson + "}"));
    }

    private ResultActions verifyPayment(String orderNo, long amount) throws Exception {
        return mockMvc.perform(post("/internal/payments/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sagaId\":\"saga-" + orderNo + "\",\"orderNo\":\"" + orderNo + "\",\"amount\":" + amount + "}"));
    }

    private String readyOrder() throws Exception {
        String orderNo = newOrderNo();
        mockMvc.perform(post("/internal/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":1,\"orderNo\":\"" + orderNo + "\",\"amount\":" + AMOUNT
                                + ",\"sagaId\":\"saga-prepare-" + orderNo + "\"}"))
                .andExpect(status().isCreated());
        return orderNo;
    }

    /** READY → 검증으로 PAID 까지 */
    private String paidOrder() throws Exception {
        String orderNo = readyOrder();
        doReturn(portOne("PAID", AMOUNT, 0)).when(portOneClient).getPayment("pay-" + orderNo);
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

    private PaymentCancel onlyCancel(String orderNo) {
        List<PaymentCancel> list = cancels(orderNo);
        assertThat(list).hasSize(1);
        return list.getFirst();
    }

    // ── 포트원 응답 끊김

    @Test
    @DisplayName("부분 취소 응답이 끊겨도 포트원 조회에 취소 내역이 있으면 그 자리에서 성공으로 확정한다")
    void timeout_reconciledImmediately() throws Exception {
        String orderNo = paidOrder();
        doThrow(timeout()).when(portOneClient).cancel(eq("pay-" + orderNo), any());
        doReturn(portOne("PARTIAL_CANCELLED", AMOUNT, 20_000L, portOneCancel("SUCCEEDED", "c-ok", 20_000L)))
                .when(portOneClient).getPayment("pay-" + orderNo);

        cancel(orderNo, "saga-" + orderNo, 20_000L).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cancel.portoneCancellationId").value("c-ok"))
                .andExpect(jsonPath("$.data.payment.status").value("PARTIAL_CANCELLED"));

        assertThat(payment(orderNo).getCancelledAmount()).isEqualTo(20_000L);
        assertThat(onlyCancel(orderNo).getStatus()).isEqualTo(CancelStatus.SUCCEEDED);
        verify(portOneClient, times(1)).cancel(eq("pay-" + orderNo), any());
    }

    @Test
    @DisplayName("응답도 끊기고 조회도 실패하면 409 PAYMENT_CANCEL_PENDING · PENDING 기록 — 같은 sagaId 재요청이 대조로 확정하고 포트원 취소는 1번만")
    void timeoutAndLookupFailure_pendingThenResolvedOnRetry() throws Exception {
        String orderNo = paidOrder();
        String sagaId = "saga-" + orderNo;
        doThrow(timeout()).when(portOneClient).cancel(eq("pay-" + orderNo), any());
        doThrow(unavailable()).when(portOneClient).getPayment("pay-" + orderNo);

        cancel(orderNo, sagaId, 20_000L).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_CANCEL_PENDING"));
        assertThat(onlyCancel(orderNo).getStatus()).isEqualTo(CancelStatus.PENDING);
        assertThat(payment(orderNo).getCancelledAmount()).isZero();   // 확정 전에는 결제 금액에 반영하지 않음

        doReturn(portOne("PARTIAL_CANCELLED", AMOUNT, 20_000L, portOneCancel("SUCCEEDED", "c-late", 20_000L)))
                .when(portOneClient).getPayment("pay-" + orderNo);
        cancel(orderNo, sagaId, 20_000L).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cancel.portoneCancellationId").value("c-late"));

        assertThat(onlyCancel(orderNo).getStatus()).isEqualTo(CancelStatus.SUCCEEDED);
        assertThat(payment(orderNo).getCancelledAmount()).isEqualTo(20_000L);
        verify(portOneClient, times(1)).cancel(eq("pay-" + orderNo), any());
    }

    @Test
    @DisplayName("포트원에 닿지 않은 취소(흔적 없음 · 잔액 그대로)는 재요청 때 같은 잔액 조건으로 다시 보낸다")
    void neverReachedPortOne_resentOnRetry() throws Exception {
        String orderNo = paidOrder();
        String sagaId = "saga-" + orderNo;
        doThrow(timeout()).doReturn(cancelResponse("SUCCEEDED", "c-2nd", AMOUNT))
                .when(portOneClient).cancel(eq("pay-" + orderNo), any());
        doReturn(portOne("PAID", AMOUNT, 0)).when(portOneClient).getPayment("pay-" + orderNo);

        cancel(orderNo, sagaId, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_CANCEL_PENDING"));
        cancel(orderNo, sagaId, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payment.status").value("CANCELLED"));

        ArgumentCaptor<PortOneCancelRequest> sent = ArgumentCaptor.forClass(PortOneCancelRequest.class);
        verify(portOneClient, times(2)).cancel(eq("pay-" + orderNo), sent.capture());
        assertThat(sent.getAllValues()).allSatisfy(r -> {
            assertThat(r.amount()).isEqualTo(AMOUNT);
            assertThat(r.currentCancellableAmount()).isEqualTo(AMOUNT);   // 두 요청이 겹쳐도 포트원이 하나만 받아들임
        });
        assertThat(onlyCancel(orderNo).getPortoneCancellationId()).isEqualTo("c-2nd");
    }

    @Test
    @DisplayName("포트원이 '이미 취소됨'으로 거절해도 조회에서 내 취소가 확인되면 성공으로 확정한다")
    void alreadyCancelledRejection_reconciled() throws Exception {
        String orderNo = paidOrder();
        doThrow(rejected(HttpStatus.CONFLICT, "PAYMENT_ALREADY_CANCELLED")).when(portOneClient).cancel(eq("pay-" + orderNo), any());
        doReturn(portOne("CANCELLED", AMOUNT, AMOUNT, portOneCancel("SUCCEEDED", "c-prev", AMOUNT)))
                .when(portOneClient).getPayment("pay-" + orderNo);

        cancel(orderNo, "saga-" + orderNo, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payment.status").value("CANCELLED"));
        assertThat(onlyCancel(orderNo).getPortoneCancellationId()).isEqualTo("c-prev");
    }

    // ── 포트원 처리 중 · 확정 거절

    @Test
    @DisplayName("포트원 REQUESTED(처리 중)는 성공으로 치지 않는다 — PENDING, 웹훅이 오면 조회로 확정")
    void requested_pendingUntilWebhook() throws Exception {
        String orderNo = paidOrder();
        doReturn(cancelResponse("REQUESTED", "c-req", AMOUNT)).when(portOneClient).cancel(eq("pay-" + orderNo), any());

        cancel(orderNo, "saga-" + orderNo, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_CANCEL_PENDING"));
        assertThat(onlyCancel(orderNo).getPortoneCancellationId()).isEqualTo("c-req");
        assertThat(payment(orderNo).getStatus()).isEqualTo(PaymentStatus.PAID);

        doReturn(portOne("CANCELLED", AMOUNT, AMOUNT, portOneCancel("SUCCEEDED", "c-req", AMOUNT)))
                .when(portOneClient).getPayment("pay-" + orderNo);
        paymentService.reconcile("pay-" + orderNo);   // 웹훅 Transaction.Cancelled

        assertThat(onlyCancel(orderNo).getStatus()).isEqualTo(CancelStatus.SUCCEEDED);
        assertThat(payment(orderNo).getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    @DisplayName("포트원 확정 거절은 FAILED · 502 — 같은 sagaId 재요청은 최신 잔액으로 다시 시도하고 기록은 1건")
    void definitiveRejection_failedThenRetried() throws Exception {
        String orderNo = paidOrder();
        String sagaId = "saga-" + orderNo;
        doThrow(rejected(HttpStatus.BAD_REQUEST, "INVALID_REQUEST")).doReturn(cancelResponse("SUCCEEDED", "c-retry", 20_000L))
                .when(portOneClient).cancel(eq("pay-" + orderNo), any());

        cancel(orderNo, sagaId, 20_000L).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXTERNAL_API_ERROR"));
        assertThat(onlyCancel(orderNo).getStatus()).isEqualTo(CancelStatus.FAILED);
        assertThat(onlyCancel(orderNo).getLastError()).contains("INVALID_REQUEST");

        cancel(orderNo, sagaId, 20_000L).andExpect(status().isOk());
        assertThat(onlyCancel(orderNo).getStatus()).isEqualTo(CancelStatus.SUCCEEDED);
        assertThat(payment(orderNo).getCancelledAmount()).isEqualTo(20_000L);
    }

    @Test
    @DisplayName("같은 결제에 확정 안 된 취소가 있으면 다른 취소는 포트원을 부르지 않고 409 PAYMENT_CANCEL_PENDING")
    void anotherCancelInFlight_rejectedWithoutCallingPortOne() throws Exception {
        String orderNo = paidOrder();
        doThrow(timeout()).when(portOneClient).cancel(eq("pay-" + orderNo), any());
        doThrow(unavailable()).when(portOneClient).getPayment("pay-" + orderNo);
        cancel(orderNo, "saga-a-" + orderNo, 10_000L).andExpect(status().isConflict());

        cancel(orderNo, "saga-b-" + orderNo, 10_000L).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_CANCEL_PENDING"));

        verify(portOneClient, times(1)).cancel(eq("pay-" + orderNo), any());
        assertThat(cancels(orderNo)).hasSize(1);
    }

    // ── 포트원 성공 후 우리 쪽 실패

    @Test
    @DisplayName("포트원 취소 성공 직후 DB 기록이 실패해도 PENDING 이 남아, 같은 sagaId 재요청이 포트원 재호출 없이 대조로 확정한다")
    void dbFailureAfterPortOneSuccess_resolvedByReconcile() throws Exception {
        String orderNo = paidOrder();
        String sagaId = "saga-" + orderNo;
        doReturn(cancelResponse("SUCCEEDED", "c-db", AMOUNT)).when(portOneClient).cancel(eq("pay-" + orderNo), any());
        doThrow(new DataAccessResourceFailureException("DB 연결 끊김")).doCallRealMethod()
                .when(paymentTx).completeCancel(anyLong(), any(), any());

        cancel(orderNo, sagaId, null).andExpect(status().isInternalServerError());
        assertThat(onlyCancel(orderNo).getStatus()).isEqualTo(CancelStatus.PENDING);
        assertThat(payment(orderNo).getStatus()).isEqualTo(PaymentStatus.PAID);

        doReturn(portOne("CANCELLED", AMOUNT, AMOUNT, portOneCancel("SUCCEEDED", "c-db", AMOUNT)))
                .when(portOneClient).getPayment("pay-" + orderNo);
        cancel(orderNo, sagaId, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payment.status").value("CANCELLED"));

        verify(portOneClient, times(1)).cancel(eq("pay-" + orderNo), any());
        assertThat(onlyCancel(orderNo).getPortoneCancellationId()).isEqualTo("c-db");
    }

    // ── 자동 환불 (금액 불일치 · 늦은 결제) — 다시 요청할 주체가 없어 스케줄러가 끝까지

    @Test
    @DisplayName("금액 불일치 환불 응답이 끊겨도 409 AMOUNT_MISMATCH · FAILED 는 바로 확정, 환불은 복구 스케줄러가 마무리")
    void mismatchRefundTimeout_finishedByScheduler() throws Exception {
        String orderNo = readyOrder();
        doReturn(portOne("PAID", 1_000L, 0)).when(portOneClient).getPayment("pay-" + orderNo);
        doThrow(timeout()).doReturn(cancelResponse("SUCCEEDED", "c-refund", 1_000L))
                .when(portOneClient).cancel(eq("pay-" + orderNo), any());

        verifyPayment(orderNo, AMOUNT).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_AMOUNT_MISMATCH"));
        assertThat(payment(orderNo).getStatus()).isEqualTo(PaymentStatus.FAILED);   // 주문에 쓰지 않는다는 결정이 먼저 남음
        assertThat(onlyCancel(orderNo).getStatus()).isEqualTo(CancelStatus.PENDING);
        assertThat(onlyCancel(orderNo).getSagaId()).isNull();

        recoveryScheduler.recover();

        assertThat(onlyCancel(orderNo).getStatus()).isEqualTo(CancelStatus.SUCCEEDED);
        assertThat(onlyCancel(orderNo).getPortoneCancellationId()).isEqualTo("c-refund");
    }

    @Test
    @DisplayName("포트원이 거절한 자동 환불은 복구 스케줄러가 최신 잔액으로 재시도한다")
    void rejectedAutoRefund_retriedByScheduler() throws Exception {
        String orderNo = readyOrder();
        doReturn(portOne("PAID", 1_000L, 0)).when(portOneClient).getPayment("pay-" + orderNo);
        doThrow(rejected(HttpStatus.BAD_REQUEST, "INVALID_REQUEST")).doReturn(cancelResponse("SUCCEEDED", "c-r2", 1_000L))
                .when(portOneClient).cancel(eq("pay-" + orderNo), any());

        verifyPayment(orderNo, AMOUNT).andExpect(status().isConflict());
        assertThat(onlyCancel(orderNo).getStatus()).isEqualTo(CancelStatus.FAILED);

        recoveryScheduler.recover();

        assertThat(onlyCancel(orderNo).getStatus()).isEqualTo(CancelStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("웹훅이 여러 번 와도 늦은 결제 환불은 1건만 — 진행 중인 환불이 있으면 새로 보내지 않는다")
    void latePaymentWebhookTwice_refundOnce() throws Exception {
        String orderNo = readyOrder();
        doReturn(portOne("READY", AMOUNT, 0)).when(portOneClient).getPayment("pay-" + orderNo);
        cancel(orderNo, "saga-" + orderNo, null).andExpect(status().isOk());   // 결제 대기 만료 보상 → FAILED
        assertThat(payment(orderNo).getStatus()).isEqualTo(PaymentStatus.FAILED);

        doReturn(portOne("PAID", AMOUNT, 0)).when(portOneClient).getPayment("pay-" + orderNo);
        doThrow(timeout()).when(portOneClient).cancel(eq("pay-" + orderNo), any());
        paymentService.reconcile("pay-" + orderNo);
        paymentService.reconcile("pay-" + orderNo);

        assertThat(cancels(orderNo)).singleElement().satisfies(c -> {
            assertThat(c.getStatus()).isEqualTo(CancelStatus.PENDING);
            assertThat(c.getReason()).isEqualTo("결제 실패 처리 후 승인된 결제 자동 취소");
        });
        verify(portOneClient, times(1)).cancel(eq("pay-" + orderNo), any());
    }

    // ── 기록 밖의 취소

    @Test
    @DisplayName("내 취소 흔적은 없는데 포트원 잔액이 바뀌었으면(콘솔 취소 등) 자동으로 다시 보내지 않고 PENDING · 수동 확인")
    void balanceChangedOutsideRecords_notResent() throws Exception {
        String orderNo = paidOrder();
        doThrow(timeout()).when(portOneClient).cancel(eq("pay-" + orderNo), any());
        doReturn(portOne("PAID", AMOUNT, 0)).when(portOneClient).getPayment("pay-" + orderNo);
        cancel(orderNo, "saga-" + orderNo, 20_000L).andExpect(status().isConflict());

        doReturn(portOne("PARTIAL_CANCELLED", AMOUNT, 5_000L, portOneCancel("SUCCEEDED", "c-console", 5_000L)))
                .when(portOneClient).getPayment("pay-" + orderNo);
        recoveryScheduler.recover();

        verify(portOneClient, times(1)).cancel(eq("pay-" + orderNo), any());
        assertThat(onlyCancel(orderNo).getStatus()).isEqualTo(CancelStatus.PENDING);
        assertThat(onlyCancel(orderNo).getLastError()).contains("잔액 불일치");
        verify(paymentTx, never()).completeCancel(eq(onlyCancel(orderNo).getId()), eq("c-console"), any());
    }
}
