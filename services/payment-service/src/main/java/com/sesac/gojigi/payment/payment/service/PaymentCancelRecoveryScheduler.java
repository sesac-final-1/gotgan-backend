package com.sesac.gojigi.payment.payment.service;

import com.sesac.gojigi.payment.common.logging.PaymentLogContext;
import com.sesac.gojigi.payment.payment.client.PortOneClientAdapter;
import com.sesac.gojigi.payment.payment.client.dto.PortOnePayment;
import com.sesac.gojigi.payment.payment.entity.CancelStatus;
import com.sesac.gojigi.payment.payment.service.PaymentCancelExecutor.Result;
import com.sesac.gojigi.payment.payment.service.PaymentTxService.CancelRef;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 취소 복구 스케줄러 — 결과를 모른 채 남은 취소를 포트원 조회로 끝까지 마무리한다 (docs/api/payment.md 8절).
 * ① PENDING 이 stale-after 동안 확정되지 않음 → 대조 → 포트원에 닿지 않았으면 다시 보냄 (PaymentCancelExecutor.resume)
 * ② 포트원이 거절한 자동 환불(sagaId 없음 — 금액 불일치 · 늦은 결제) → 최신 잔액으로 재시도, max-attempts 까지
 * 시도 횟수가 경보 기준을 넘으면 ERROR 로그 + payment_cancel_unresolved_total 지표 (사람이 확인할 대상).
 * ShedLock — 레플리카가 늘어도 한 파드만 실행.
 */
@Slf4j
@Component
public class PaymentCancelRecoveryScheduler {

    private final PaymentTxService paymentTx;
    private final PaymentCancelExecutor cancelExecutor;
    private final PortOneClientAdapter portOne;
    private final Duration staleAfter;
    private final int batchSize;
    private final int maxAttempts;
    private final Counter unresolved;

    public PaymentCancelRecoveryScheduler(PaymentTxService paymentTx, PaymentCancelExecutor cancelExecutor,
            PortOneClientAdapter portOne, MeterRegistry meterRegistry,
            @Value("${app.cancel-recovery.stale-after:60s}") Duration staleAfter,
            @Value("${app.cancel-recovery.batch-size:20}") int batchSize,
            @Value("${app.cancel-recovery.max-attempts:10}") int maxAttempts) {
        this.paymentTx = paymentTx;
        this.cancelExecutor = cancelExecutor;
        this.portOne = portOne;
        this.staleAfter = staleAfter;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.unresolved = Counter.builder("payment.cancel.unresolved")
                .description("복구 스케줄러가 시도 한도를 넘겨도 확정하지 못한 취소 · 환불 (수동 확인 대상)")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${app.cancel-recovery.interval:60s}",
            initialDelayString = "${app.cancel-recovery.initial-delay:30s}")
    @SchedulerLock(name = "payment-cancel-recovery", lockAtMostFor = "5m")
    public void recover() {
        paymentTx.stalePendingCancels(staleAfter, batchSize).forEach(this::resumePending);
        paymentTx.retryableFailedRefunds(staleAfter, maxAttempts, batchSize).forEach(this::retryRefund);
    }

    private void resumePending(CancelRef cancel) {
        PaymentLogContext.put(cancel.orderNo(), cancel.sagaId());   // 요청 밖(스케줄러)이라 필터가 없음 — 건마다 넣고 지운다
        try {
            Result result = cancelExecutor.resume(cancel);
            log.info("취소 복구 - cancelId={}, status={}, attempt={}", cancel.cancelId(), result.status(), cancel.attemptCount() + 1);
            alertIfStuck(cancel, result);
        } catch (RuntimeException e) {
            log.warn("취소 복구 실패 — 다음 주기에 다시 cancelId={}, cause={}", cancel.cancelId(), e.toString());
        } finally {
            PaymentLogContext.clear();
        }
    }

    private void retryRefund(CancelRef cancel) {
        PaymentLogContext.put(cancel.orderNo(), cancel.sagaId());
        try {
            Optional<PortOnePayment> latest = portOne.getPayment(cancel.portonePaymentId());
            if (latest.isEmpty() || latest.get().cancellableAmount() <= 0) {
                // 환불할 잔액이 없음 — 포트원에서 이미 전액 취소됨(콘솔 등). 기록과 다르니 사람이 확인
                log.error("거절된 자동 환불 — 포트원 잔액 없음, 수동 확인 cancelId={}", cancel.cancelId());
                unresolved.increment();
                paymentTx.failCancelAttempt(cancel.cancelId(), "포트원 잔액 없음 — 수동 확인");
                return;
            }
            Result result = cancelExecutor.execute(paymentTx.retryFailed(cancel.cancelId(), latest.get().cancellableAmount()));
            log.info("자동 환불 재시도 - cancelId={}, status={}, attempt={}", cancel.cancelId(), result.status(),
                    cancel.attemptCount() + 1);
            alertIfStuck(cancel, result);
        } catch (RuntimeException e) {
            log.warn("자동 환불 재시도 실패 — 다음 주기에 다시 cancelId={}, cause={}", cancel.cancelId(), e.toString());
        } finally {
            PaymentLogContext.clear();
        }
    }

    private void alertIfStuck(CancelRef cancel, Result result) {
        if (result.status() != CancelStatus.SUCCEEDED && cancel.attemptCount() + 1 >= maxAttempts) {
            unresolved.increment();
            log.error("취소 · 환불이 {}회 시도에도 확정되지 않음 — 수동 확인 필요 cancelId={}, sagaId={}, amount={}, status={}, cause={}",
                    maxAttempts, cancel.cancelId(), cancel.sagaId(), cancel.amount(), result.status(), result.error());
        }
    }
}
