package com.sesac.gojigi.order.saga.service;

import com.sesac.gojigi.order.order.client.dto.UserSummaryResponse;
import com.sesac.gojigi.order.order.dto.request.OrderCreateRequest;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.service.OrderPricing;
import com.sesac.gojigi.order.order.service.OrderTxService;
import com.sesac.gojigi.order.order.service.OrderTxService.PendingOrder;
import com.sesac.gojigi.order.saga.entity.SagaInstance;
import com.sesac.gojigi.order.saga.entity.SagaStatus;
import com.sesac.gojigi.order.saga.entity.SagaStep;
import com.sesac.gojigi.order.saga.entity.SagaType;
import com.sesac.gojigi.order.saga.repository.SagaInstanceRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * saga_instance 기록 — 메서드마다 짧은 트랜잭션 하나 (가이드라인 06장).
 * 참여자를 부르기 전에 sagaId 가 커밋돼 있어야 결과를 모르는 호출도 같은 sagaId 로 다시 부를 수 있다.
 */
@Service
@RequiredArgsConstructor
public class OrderSagaTxService {

    /** 결제창을 띄운 뒤 — 결제 대기(PAYMENT_READY) · 검증 성공 뒤 PAID 마무리 전(PAYMENT_VERIFIED) */
    private static final Set<SagaStep> PAYMENT_WAITING = Set.of(SagaStep.PAYMENT_READY, SagaStep.PAYMENT_VERIFIED);
    /** 결제창을 띄우기 전 — [요청 1] 처리 중에 멈춘 단계 */
    private static final Set<SagaStep> BEFORE_PAYMENT = Set.of(SagaStep.ORDER_CREATED, SagaStep.POINT_USED,
            SagaStep.STOCK_RESERVED);

    private final SagaInstanceRepository sagaInstanceRepository;
    private final OrderTxService orderTx;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /** 주문 PENDING 과 주문 Saga — Saga 가 있어야 스케줄러가 찾으므로 */
    public record PlacedOrder(PendingOrder pending, SagaInstance saga) {
    }

    /**
     * 주문 PENDING + 주문 Saga 를 한 트랜잭션으로 (A-경-4) — 따로 커밋하면 사이에 파드가 죽었을 때
     * Saga 없는 PENDING 주문이 남고, 결제 대기 만료 · 복구 스케줄러는 saga_instance 만 보므로 영원히 PENDING 이다.
     * 주문번호 충돌(DataIntegrityViolationException)이면 둘 다 롤백 — 호출 쪽이 다시 부른다.
     */
    @Transactional
    public PlacedOrder startPlaceOrder(UserSummaryResponse buyer, OrderCreateRequest request, OrderPricing.Result priced,
                                       Map<Long, String> storeNames) {
        PendingOrder pending = orderTx.createPending(buyer, request, priced, storeNames);
        return new PlacedOrder(pending, startPlaceOrder(pending.order()));
    }

    @Transactional
    public SagaInstance startPlaceOrder(Order order) {
        return sagaInstanceRepository.save(SagaInstance.placeOrder(order, UUID.randomUUID().toString()));
    }

    @Transactional
    public void advance(Long sagaInstanceId, SagaStep step) {
        get(sagaInstanceId).advance(step);
    }

    @Transactional
    public void complete(Long sagaInstanceId) {
        get(sagaInstanceId).complete();
    }

    @Transactional
    public void startCompensating(Long sagaInstanceId, String reason) {
        get(sagaInstanceId).startCompensating(reason);
    }

    @Transactional
    public void compensated(Long sagaInstanceId) {
        SagaInstance saga = get(sagaInstanceId);
        saga.compensated();
        count("saga.compensated", saga);   // Prometheus: saga_compensated_total{type}
    }

    /** 보상이 끝나지 않음 — Saga 복구 스케줄러가 nextRetryAt 에 같은 sagaId 로 다시 시도 (한도 넘으면 FAILED) */
    @Transactional
    public void scheduleRetry(Long sagaInstanceId, String reason) {
        SagaInstance saga = get(sagaInstanceId);
        saga.scheduleRetry(reason, LocalDateTime.now(clock));
        // saga_failed_total = 재시도 한도를 넘어 사람이 확인해야 함 (알림 대상) · saga_retry_total = 재시도 예약
        count(saga.getStatus() == SagaStatus.FAILED ? "saga.failed" : "saga.retry", saga);
    }

    /** 보상 · 복구 지표 (B-경-1) — 알림 규칙은 이 이름으로 건다 */
    private void count(String name, SagaInstance saga) {
        Counter.builder(name).tag("type", saga.getSagaType().name()).register(meterRegistry).increment();
    }

    /** 주문의 주문 생성 Saga */
    @Transactional(readOnly = true)
    public SagaInstance getPlaceOrder(Long orderId) {
        return sagaInstanceRepository.findByOrderIdAndSagaType(orderId, SagaType.PLACE_ORDER)
                .orElseThrow(() -> new IllegalStateException("주문 Saga 없음 - orderId=" + orderId));
    }

    /** 결제 대기가 cutoff 전부터 이어지는 Saga — 결제 대기 만료 스케줄러 대상 (검증 뒤 멈춘 PAYMENT_VERIFIED 포함) */
    @Transactional(readOnly = true)
    public List<SagaInstance> findPaymentWaiting(LocalDateTime cutoff, int limit) {
        return sagaInstanceRepository.findStalled(SagaType.PLACE_ORDER, SagaStatus.STARTED, PAYMENT_WAITING, cutoff,
                PageRequest.of(0, limit));
    }

    /**
     * 복구 스케줄러 대상 — 결제 준비 전 단계에서 cutoff 전부터 멈춘 STARTED(파드가 Saga 도중 종료),
     * 재시도 시각이 지났거나 cutoff 전부터 멈춘 COMPENSATING.
     */
    @Transactional(readOnly = true)
    public List<SagaInstance> findRecoverable(LocalDateTime cutoff, int limit) {
        List<SagaInstance> sagas = new ArrayList<>(sagaInstanceRepository.findStalled(SagaType.PLACE_ORDER,
                SagaStatus.STARTED, BEFORE_PAYMENT, cutoff, PageRequest.of(0, limit)));
        sagas.addAll(sagaInstanceRepository.findRetryDue(SagaType.PLACE_ORDER, SagaStatus.COMPENSATING,
                LocalDateTime.now(clock), cutoff, PageRequest.of(0, limit)));
        return sagas;
    }

    /** 다시 진행할 취소 · 반품 Saga — 결제 취소 미확정 · 장애로 재시도 시각이 됐거나, 재시도 시각 없이 cutoff 전부터 멈춘 STARTED */
    @Transactional(readOnly = true)
    public List<SagaInstance> findCancelRetryable(LocalDateTime cutoff, int limit) {
        List<SagaInstance> sagas = new ArrayList<>(sagaInstanceRepository.findRetryDue(SagaType.CANCEL_ITEM,
                SagaStatus.STARTED, LocalDateTime.now(clock), cutoff, PageRequest.of(0, limit)));
        sagas.addAll(sagaInstanceRepository.findRetryDue(SagaType.RETURN_ITEM, SagaStatus.STARTED,
                LocalDateTime.now(clock), cutoff, PageRequest.of(0, limit)));
        return sagas;
    }

    private SagaInstance get(Long sagaInstanceId) {
        return sagaInstanceRepository.findById(sagaInstanceId)
                .orElseThrow(() -> new IllegalStateException("saga_instance 없음 - id=" + sagaInstanceId));
    }
}
