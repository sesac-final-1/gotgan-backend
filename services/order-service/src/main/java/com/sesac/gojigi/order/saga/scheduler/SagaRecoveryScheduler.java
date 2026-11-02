package com.sesac.gojigi.order.saga.scheduler;

import com.sesac.gojigi.order.common.logging.OrderLogContext;
import com.sesac.gojigi.order.saga.entity.SagaInstance;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import com.sesac.gojigi.order.saga.service.CancelOrderItemSaga;
import com.sesac.gojigi.order.saga.service.OrderSagaTxService;
import com.sesac.gojigi.order.saga.service.PlaceOrderSaga;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 멈춘 Saga 복구 (ORD-07) — 결제 준비 전 단계에서 stalled-after(5분) 넘게 멈춘 Saga(파드가 [요청 1] 도중 종료)와
 * 보상 호출이 실패해 재시도 시각(1 · 1 · 2분 후 5분, 최대 8회)이 된 Saga 를 다시 보상한다. 결제 대기는 PaymentExpiryScheduler 몫.
 * 취소 Saga(CLM-01)도 같은 기준으로 멈춘 단계부터 다시 진행한다 (결제 취소 409 PAYMENT_CANCEL_PENDING · 장애).
 */
@Slf4j
@Component
public class SagaRecoveryScheduler {

    private final PlaceOrderSaga placeOrderSaga;
    private final CancelOrderItemSaga cancelOrderItemSaga;
    private final OrderSagaTxService sagaTx;
    private final Clock clock;
    private final Duration stalledAfter;
    private final int batchSize;
    private final Duration maxRun;

    public SagaRecoveryScheduler(PlaceOrderSaga placeOrderSaga, CancelOrderItemSaga cancelOrderItemSaga,
                                 OrderSagaTxService sagaTx, Clock clock,
                                 @Value("${app.scheduler.saga-recovery.stalled-after:5m}") Duration stalledAfter,
                                 @Value("${app.scheduler.batch-size:100}") int batchSize,
                                 @Value("${app.scheduler.max-run:4m}") Duration maxRun) {
        this.placeOrderSaga = placeOrderSaga;
        this.cancelOrderItemSaga = cancelOrderItemSaga;
        this.sagaTx = sagaTx;
        this.clock = clock;
        this.stalledAfter = stalledAfter;
        this.batchSize = batchSize;
        this.maxRun = maxRun;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.saga-recovery.interval:60s}",
            initialDelayString = "${app.scheduler.saga-recovery.initial-delay:30s}")
    @SchedulerLock(name = "saga-recovery", lockAtMostFor = "5m")   // max-run 이 이보다 짧아야 한다
    public void recoverStalledSagas() {
        Instant deadline = clock.instant().plus(maxRun);
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(stalledAfter);
        if (recoverUntil(deadline, sagaTx.findRecoverable(cutoff, batchSize), placeOrderSaga::recover)) {
            recoverUntil(deadline, sagaTx.findCancelRetryable(cutoff, batchSize), cancelOrderItemSaga::resume);
        }
    }

    /**
     * 실행 시간 상한(max-run)까지만 — 잠금(lockAtMostFor)이 풀린 뒤에도 돌면 다른 파드가 같은 Saga 를 동시에 처리한다 (B-경-2).
     * 상한에 닿으면 false, 남은 건은 다음 주기에.
     */
    private boolean recoverUntil(Instant deadline, List<SagaInstance> sagas, Consumer<SagaInstance> action) {
        for (int i = 0; i < sagas.size(); i++) {
            if (!clock.instant().isBefore(deadline)) {
                log.warn("Saga 복구 — 실행 시간 상한 {} 도달, 남은 {}건은 다음 주기에", maxRun, sagas.size() - i);
                return false;
            }
            recover(sagas.get(i), action);
        }
        return true;
    }

    private void recover(SagaInstance saga, Consumer<SagaInstance> action) {
        OrderLogContext.put(saga.getOrder().getOrderNo(), saga.getSagaId());
        try {
            action.accept(saga);
        } catch (RuntimeException e) {
            log.warn("Saga 복구 실패 — 다음 주기에 다시 sagaId={}, cause={}", saga.getSagaId(), e.toString());
        } finally {
            OrderLogContext.clear();
        }
    }
}
