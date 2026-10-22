package com.sesac.gojigi.order.saga.entity;

import com.sesac.gojigi.order.claim.entity.OrderClaim;
import com.sesac.gojigi.order.common.entity.BaseTimeEntity;
import com.sesac.gojigi.order.order.entity.Order;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Saga 진행 상태 (오케스트레이터의 기억) — saga → order · claim 단방향.
 * sagaId(UUID)는 참여자 API(user · product · payment) 멱등 키라, 참여자를 부르기 전에 이 행을 먼저 커밋한다.
 * 결과를 모르는 호출(409 PAYMENT_CANCEL_PENDING · 타임아웃 · 5xx)은 같은 sagaId 로 재시도 —
 * attemptCount · nextRetryAt (V3, 1 · 1 · 2분 후 5분 상한, 최대 8회 ≈ 30분).
 */
@Getter
@Entity
@Table(name = "saga_instance")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SagaInstance extends BaseTimeEntity {

    /** last_error VARCHAR(500) */
    private static final int ERROR_MAX = 500;

    /** 재시도 간격 — 1 · 2 · 4 · 9 · 14 · 19 · 24 · 29분, 최대 8회 ≈ 30분 (payment 복구 주기 60초보다 짧게 다시 부르지 않음) */
    static final List<Duration> RETRY_DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(1),
            Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(5), Duration.ofMinutes(5),
            Duration.ofMinutes(5), Duration.ofMinutes(5));

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "saga_instance_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** 취소 · 반품 Saga 일 때만 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_claim_id")
    private OrderClaim orderClaim;

    @Column(name = "saga_id", nullable = false, unique = true, length = 36, columnDefinition = "CHAR(36)")
    private String sagaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "saga_type", nullable = false, length = 30)
    private SagaType sagaType;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", nullable = false, length = 30)
    private SagaStep currentStep;

    /** 현재 단계 재시도 횟수 — 단계가 넘어가면 0 */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SagaStatus status;

    /** 다음 재시도 시각 — NULL 이면 재시도 대상 아님 */
    @Column(name = "next_retry_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime nextRetryAt;

    private SagaInstance(Order order, OrderClaim orderClaim, String sagaId, SagaType sagaType, SagaStep currentStep) {
        this.order = order;
        this.orderClaim = orderClaim;
        this.sagaId = sagaId;
        this.sagaType = sagaType;
        this.currentStep = currentStep;
        this.status = SagaStatus.STARTED;
    }

    /** 주문 Saga — 주문 행을 만든 직후 */
    public static SagaInstance placeOrder(Order order, String sagaId) {
        return new SagaInstance(order, null, sagaId, SagaType.PLACE_ORDER, SagaStep.ORDER_CREATED);
    }

    /** 취소 · 반품 Saga — 클레임 승인 직후, 결제 취소 요청 전 */
    public static SagaInstance forClaim(OrderClaim claim, String sagaId, SagaType sagaType) {
        return new SagaInstance(claim.getOrderItem().getOrder(), claim, sagaId, sagaType,
                SagaStep.PAYMENT_CANCEL_REQUESTED);
    }

    /** 단계를 마침 — 재시도 기록은 단계마다 새로 센다 */
    public void advance(SagaStep step) {
        this.currentStep = step;
        this.attemptCount = 0;
        this.nextRetryAt = null;
    }

    public void complete() {
        advance(SagaStep.DONE);
        this.status = SagaStatus.COMPLETED;
    }

    /** 보상 시작 — 원인을 남긴다 */
    public void startCompensating(String reason) {
        this.status = SagaStatus.COMPENSATING;
        this.lastError = truncate(reason);
    }

    public void compensated() {
        this.status = SagaStatus.COMPENSATED;
        this.nextRetryAt = null;
    }

    /**
     * 결과를 모르는 호출(409 PAYMENT_CANCEL_PENDING · 타임아웃 · 5xx)이나 실패한 보상 — 같은 sagaId 로 나중에 다시 시도한다.
     * 간격 1 · 1 · 2분 후 5분 상한, 8회를 넘기면 FAILED(사람이 확인). 재시도는 Saga 복구 스케줄러가 nextRetryAt 으로 고른다.
     */
    public void scheduleRetry(String reason, LocalDateTime now) {
        this.lastError = truncate(reason);
        if (attemptCount >= RETRY_DELAYS.size()) {
            this.status = SagaStatus.FAILED;
            this.nextRetryAt = null;
            return;
        }
        this.nextRetryAt = now.plus(RETRY_DELAYS.get(attemptCount));
        this.attemptCount++;
    }

    private static String truncate(String reason) {
        return reason == null || reason.length() <= ERROR_MAX ? reason : reason.substring(0, ERROR_MAX);
    }
}
