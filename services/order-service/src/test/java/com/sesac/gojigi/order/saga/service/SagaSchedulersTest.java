package com.sesac.gojigi.order.saga.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.order.common.logging.OrderLogContext;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderFixtures;
import com.sesac.gojigi.order.saga.entity.SagaInstance;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import com.sesac.gojigi.order.saga.scheduler.PaymentExpiryScheduler;
import com.sesac.gojigi.order.saga.scheduler.SagaRecoveryScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

/** 결제 대기 만료 · Saga 복구 스케줄러 — 기준 시각 계산 · 건마다 MDC · 한 건이 실패해도 다음 건 계속 */
@ExtendWith(MockitoExtension.class)
class SagaSchedulersTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 12, 0);

    @Mock private PlaceOrderSaga placeOrderSaga;
    @Mock private CancelOrderItemSaga cancelOrderItemSaga;
    @Mock private OrderSagaTxService sagaTx;

    private final List<String> orderNosSeen = new ArrayList<>();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static SagaInstance saga(String orderNo, String sagaId) {
        Order order = OrderFixtures.order();
        ReflectionTestUtils.setField(order, "orderNo", orderNo);
        return SagaInstance.placeOrder(order, sagaId);
    }

    @Test
    @DisplayName("결제 대기 만료 — now - payment-timeout 이전 Saga 를 건마다 MDC 에 넣고 처리, 실패해도 다음 건 계속")
    void paymentExpiry_eachSaga() {
        // given
        SagaInstance first = saga("20261022-000001", "saga-1");
        SagaInstance second = saga("20261022-000002", "saga-2");
        given(sagaTx.findPaymentWaiting(NOW.minusMinutes(15), 100)).willReturn(List.of(first, second));
        willThrow(new IllegalStateException("boom")).given(placeOrderSaga).expire(first);
        willAnswer(inv -> orderNosSeen.add(MDC.get(OrderLogContext.ORDER_NO))).given(placeOrderSaga).expire(second);
        PaymentExpiryScheduler scheduler = new PaymentExpiryScheduler(placeOrderSaga, sagaTx, CLOCK,
                Duration.ofMinutes(15), 100);

        // when
        scheduler.expireWaitingPayments();

        // then
        verify(placeOrderSaga).expire(second);
        assertThat(orderNosSeen).containsExactly("20261022-000002");
        assertThat(MDC.get(OrderLogContext.ORDER_NO)).isNull();
    }

    @Test
    @DisplayName("Saga 복구 — now - stalled-after 기준으로 고른 Saga 를 건마다 처리, 실패해도 다음 건 계속")
    void sagaRecovery_eachSaga() {
        // given
        SagaInstance first = saga("20261022-000001", "saga-1");
        SagaInstance second = saga("20261022-000002", "saga-2");
        SagaInstance cancel = saga("20261022-000003", "saga-c1");
        given(sagaTx.findRecoverable(NOW.minusMinutes(5), 50)).willReturn(List.of(first, second));
        given(sagaTx.findCancelRetryable(NOW.minusMinutes(5), 50)).willReturn(List.of(cancel));
        willThrow(new IllegalStateException("boom")).given(placeOrderSaga).recover(first);
        willAnswer(inv -> orderNosSeen.add(MDC.get(OrderLogContext.SAGA_ID))).given(placeOrderSaga).recover(second);
        willAnswer(inv -> orderNosSeen.add(MDC.get(OrderLogContext.SAGA_ID))).given(cancelOrderItemSaga).resume(cancel);
        SagaRecoveryScheduler scheduler = new SagaRecoveryScheduler(placeOrderSaga, cancelOrderItemSaga, sagaTx, CLOCK,
                Duration.ofMinutes(5), 50);

        // when
        scheduler.recoverStalledSagas();

        // then — 주문 Saga 는 보상, 취소 Saga 는 멈춘 단계부터 이어서
        verify(placeOrderSaga).recover(second);
        verify(cancelOrderItemSaga).resume(cancel);
        assertThat(orderNosSeen).containsExactly("saga-2", "saga-c1");
        assertThat(MDC.get(OrderLogContext.SAGA_ID)).isNull();
    }
}
