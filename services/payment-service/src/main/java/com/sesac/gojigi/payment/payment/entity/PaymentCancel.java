package com.sesac.gojigi.payment.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 포트원 취소 성공 1건 = 1행 (로그성 — 수정 없음, created_at 만). saga_id UNIQUE 가 같은 취소 재요청을 막는다.
 * 금액 불일치 자동 취소는 saga_id 없이(NULL) 기록한다.
 */
@Getter
@Entity
@Table(name = "payment_cancel")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentCancel {

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

    @Column(name = "reason", nullable = false, length = 200)
    private String reason;

    @Column(name = "portone_cancellation_id", length = 64)
    private String portoneCancellationId;

    @Column(name = "cancelled_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime cancelledAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    private PaymentCancel(Payment payment, Long orderItemId, String sagaId, long cancelAmount, String reason,
            String portoneCancellationId, LocalDateTime cancelledAt) {
        this.payment = payment;
        this.orderItemId = orderItemId;
        this.sagaId = sagaId;
        this.cancelAmount = cancelAmount;
        this.reason = reason;
        this.portoneCancellationId = portoneCancellationId;
        this.cancelledAt = cancelledAt;
    }

    public static PaymentCancel of(Payment payment, Long orderItemId, String sagaId, long cancelAmount, String reason,
            String portoneCancellationId, LocalDateTime cancelledAt) {
        return new PaymentCancel(payment, orderItemId, sagaId, cancelAmount, reason, portoneCancellationId, cancelledAt);
    }
}
