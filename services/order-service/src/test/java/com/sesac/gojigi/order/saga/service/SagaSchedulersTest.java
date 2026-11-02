package com.sesac.gojigi.order.saga.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
    private static final Duration MAX_RUN = Duration.ofMinutes(4);

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
                Duration.ofMinutes(15), 100, MAX_RUN);

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
                Duration.ofMinutes(5), 50, MAX_RUN);

        // when
        scheduler.recoverStalledSagas();

        // then — 주문 Saga 는 보상, 취소 Saga 는 멈춘 단계부터 이어서
        verify(placeOrderSaga).recover(second);
        verify(cancelOrderItemSaga).resume(cancel);
        assertThat(orderNosSeen).containsExactly("saga-2", "saga-c1");
        assertThat(MDC.get(OrderLogContext.SAGA_ID)).isNull();
    }

    /** 처리할 때마다 2분씩 가는 시계 — 실행 시간 상한(4분) 확인용 */
    private static final class TickingClock extends Clock {

        private Instant now = CLOCK.instant();

        void tick() {
            now = now.plus(Duration.ofMinutes(2));
        }

        @Override
        public ZoneId getZone() {
            return CLOCK.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("결제 대기 만료 — 실행 시간 상한(max-run)에 닿으면 남은 건은 다음 주기로 (B-경-2)")
    void paymentExpiry_stopsAtMaxRun() {
        // given — 한 건에 2분씩, 상한 4분이면 두 건만
        TickingClock clock = new TickingClock();
        List<SagaInstance> sagas = List.of(saga("20261022-000001", "saga-1"), saga("20261022-000002", "saga-2"),
                saga("20261022-000003", "saga-3"));
        given(sagaTx.findPaymentWaiting(NOW.minusMinutes(15), 100)).willReturn(sagas);
        willAnswer(inv -> {
            clock.tick();
            return null;
        }).given(placeOrderSaga).expire(any());

        // when
        new PaymentExpiryScheduler(placeOrderSaga, sagaTx, clock, Duration.ofMinutes(15), 100, MAX_RUN)
                .expireWaitingPayments();

        // then
        verify(placeOrderSaga, times(2)).expire(any());
        verify(placeOrderSaga, never()).expire(sagas.get(2));
    }

    @Test
    @DisplayName("Saga 복구 — 주문 Saga 에서 상한에 닿으면 취소 Saga 는 조회하지 않고 다음 주기로")
    void sagaRecovery_stopsAtMaxRun() {
        // given
        TickingClock clock = new TickingClock();
        List<SagaInstance> sagas = List.of(saga("20261022-000001", "saga-1"), saga("20261022-000002", "saga-2"),
                saga("20261022-000003", "saga-3"));
        given(sagaTx.findRecoverable(NOW.minusMinutes(5), 50)).willReturn(sagas);
        willAnswer(inv -> {
            clock.tick();
            return null;
        }).given(placeOrderSaga).recover(any());

        // when
        new SagaRecoveryScheduler(placeOrderSaga, cancelOrderItemSaga, sagaTx, clock, Duration.ofMinutes(5), 50, MAX_RUN)
                .recoverStalledSagas();

        // then
        verify(placeOrderSaga, times(2)).recover(any());
        verify(sagaTx, never()).findCancelRetryable(any(), anyInt());
        verifyNoInteractions(cancelOrderItemSaga);
    }
}
