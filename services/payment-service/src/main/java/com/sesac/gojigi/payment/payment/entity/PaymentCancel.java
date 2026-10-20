package com.sesac.gojigi.payment.payment.entity;

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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * 포트원 취소 1건 = 1행. 포트원을 부르기 전에 PENDING 으로 남기고(V2 선기록), 결과가 확정되면 SUCCEEDED / FAILED 로 바꾼다.
 * saga_id UNIQUE 가 같은 취소 재요청을 막는다. 금액 불일치 · 늦은 결제 자동 환불은 saga_id 없이(NULL) 기록한다.
 * 상태 전이는 이 엔티티 메서드로만 한다 — PENDING 외 상태에서 확정 메서드를 부르면 무시하지 않고 예외 (잘못된 흐름을 숨기지 않음).
 */
@Getter
@Entity
@Table(name = "payment_cancel")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentCancel {

    private static final int ERROR_MAX = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_cancel_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(name = "order_item_id")
    private Long orderItemId;

    @Column(name = "saga_id", unique = true, length = 36, columnDefinition = "CHAR(36)")
    private String sagaId;

    @Column(name = "cancel_amount", nullable = false)
    private long cancelAmount;

    @Column(name = "cancellable_before")
    private Long cancellableBefore;

    @Column(name = "reason", nullable = false, length = 200)
    private String reason;

    @Column(name = "portone_cancellation_id", length = 64)
    private String portoneCancellationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CancelStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", length = ERROR_MAX)
    private String lastError;

    @Column(name = "cancelled_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime cancelledAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    @Generated(event = { EventType.INSERT, EventType.UPDATE })
    @Column(name = "updated_at", insertable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime updatedAt;

    private PaymentCancel(Payment payment, Long orderItemId, String sagaId, long cancelAmount, long cancellableBefore,
            String reason) {
        this.payment = payment;
        this.orderItemId = orderItemId;
        this.sagaId = sagaId;
        this.cancelAmount = cancelAmount;
        this.cancellableBefore = cancellableBefore;
        this.reason = reason;
        this.status = CancelStatus.PENDING;
    }

    /** 포트원 취소 요청 직전 선기록 — cancellableBefore 는 포트원 currentCancellableAmount 로 그대로 보낸다 */
    public static PaymentCancel pending(Payment payment, Long orderItemId, String sagaId, long cancelAmount,
            long cancellableBefore, String reason) {
        return new PaymentCancel(payment, orderItemId, sagaId, cancelAmount, cancellableBefore, reason);
    }

    public boolean isPending() {
        return status == CancelStatus.PENDING;
    }

    public boolean isSucceeded() {
        return status == CancelStatus.SUCCEEDED;
    }

    /** 포트원 요청 · 대조를 한 번 시도할 때마다 */
    public void countAttempt() {
        this.attemptCount++;
    }

    /** 포트원이 받았지만 아직 처리 중(REQUESTED) — 취소 내역 id 를 기억해 두고 PENDING 유지 */
    public void markRequested(String portoneCancellationId) {
        requirePending();
        this.portoneCancellationId = portoneCancellationId;
        this.lastError = "포트원 취소 처리 중(REQUESTED)";
    }

    /** 결과 미확정으로 PENDING 유지 — 사유만 남긴다 */
    public void keepPending(String error) {
        requirePending();
        this.lastError = truncate(error);
    }

    public void succeed(String portoneCancellationId, LocalDateTime cancelledAt) {
        requirePending();
        this.status = CancelStatus.SUCCEEDED;
        this.portoneCancellationId = portoneCancellationId;
        this.cancelledAt = cancelledAt;
        this.lastError = null;
    }

    public void fail(String error) {
        requirePending();
        this.status = CancelStatus.FAILED;
        this.lastError = truncate(error);
    }

    /** FAILED 인 채로 재시도도 못 한 경우 — 시도 횟수만 올려 복구 스케줄러 재시도 한도에 닿게 한다 */
    public void recordFailedAttempt(String error) {
        if (status != CancelStatus.FAILED) {
            throw new IllegalStateException("FAILED 취소만 기록할 수 있습니다. status=" + status);
        }
        this.attemptCount++;
        this.lastError = truncate(error);
    }

    /** FAILED 를 같은 sagaId 로 다시 요청 — 최신 잔액으로 PENDING 부터 다시 */
    public void retry(long cancellableBefore) {
        if (status != CancelStatus.FAILED) {
            throw new IllegalStateException("FAILED 취소만 재시도할 수 있습니다. status=" + status);
        }
        this.status = CancelStatus.PENDING;
        this.cancellableBefore = cancellableBefore;
        this.portoneCancellationId = null;
    }

    private void requirePending() {
        if (status != CancelStatus.PENDING) {
            throw new IllegalStateException("PENDING 취소만 확정할 수 있습니다. id=" + id + ", status=" + status);
        }
    }

    private static String truncate(String error) {
        return error == null || error.length() <= ERROR_MAX ? error : error.substring(0, ERROR_MAX);
    }
}
