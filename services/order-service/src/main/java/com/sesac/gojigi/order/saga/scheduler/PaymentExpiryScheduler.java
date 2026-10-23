package com.sesac.gojigi.order.saga.scheduler;

import com.sesac.gojigi.order.common.logging.OrderLogContext;
import com.sesac.gojigi.order.saga.entity.SagaInstance;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import com.sesac.gojigi.order.saga.service.OrderSagaTxService;
import com.sesac.gojigi.order.saga.service.PlaceOrderSaga;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 결제 대기 만료 (ORD-07) — 결제 대기가 payment-timeout(15분, 데모는 ConfigMap 으로 짧게)을 넘긴 주문을 payment 에 다시 물어 확정 · 보상.
 * saga 패키지에 둔다 — PlaceOrderSaga 를 부르므로 order 패키지에 두면 order ↔ saga 가 서로 참조한다.
 */
@Slf4j
@Component
public class PaymentExpiryScheduler {

    private final PlaceOrderSaga placeOrderSaga;
    private final OrderSagaTxService sagaTx;
    private final Clock clock;
    private final Duration paymentTimeout;
    private final int batchSize;

    public PaymentExpiryScheduler(PlaceOrderSaga placeOrderSaga, OrderSagaTxService sagaTx, Clock clock,
                                  @Value("${app.order.payment-timeout}") Duration paymentTimeout,
                                  @Value("${app.scheduler.batch-size:100}") int batchSize) {
        this.placeOrderSaga = placeOrderSaga;
        this.sagaTx = sagaTx;
        this.clock = clock;
        this.paymentTimeout = paymentTimeout;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.payment-expiry.interval:60s}",
            initialDelayString = "${app.scheduler.payment-expiry.initial-delay:30s}")
    @SchedulerLock(name = "payment-expiry", lockAtMostFor = "5m")   // 레플리카가 2개여도 한 파드만
    public void expireWaitingPayments() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(paymentTimeout);
        sagaTx.findPaymentWaiting(cutoff, batchSize).forEach(this::expire);
    }

    private void expire(SagaInstance saga) {
        OrderLogContext.put(saga.getOrder().getOrderNo(), saga.getSagaId());   // 요청 밖이라 필터가 없음 — 건마다 넣고 지운다
        try {
            placeOrderSaga.expire(saga);
        } catch (RuntimeException e) {
            log.warn("결제 대기 만료 실패 — 다음 주기에 다시 sagaId={}, cause={}", saga.getSagaId(), e.toString());
        } finally {
            OrderLogContext.clear();
        }
    }
}
