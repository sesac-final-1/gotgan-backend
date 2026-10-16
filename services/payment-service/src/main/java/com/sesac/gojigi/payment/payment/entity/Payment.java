package com.sesac.gojigi.payment.payment.entity;

import com.sesac.gojigi.payment.common.entity.BaseTimeEntity;
import com.sesac.gojigi.payment.payment.exception.PaymentErrorCode;
import com.sesac.gojigi.payment.payment.exception.PaymentException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 결제 1건 = 주문 1건 (order_no UNIQUE). 상태 전이는 이 엔티티 메서드로만 한다 (docs/api/payment.md).
 * PAID 판정은 포트원 조회 결과로만 — 이 엔티티는 판정 결과를 기록할 뿐이다.
 */
@Getter
@Entity
@Table(name = "payment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseTimeEntity {

    private static final String PORTONE_ID_PREFIX = "pay-";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "order_no", nullable = false, unique = true, length = 20)
    private String orderNo;

    @Column(name = "portone_payment_id", nullable = false, unique = true, length = 64)
    private String portonePaymentId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "cancelled_amount", nullable = false)
    private long cancelledAmount;

    @Column(name = "pg_tx_id", length = 100)
    private String pgTxId;

    @Column(name = "method", length = 30)
    private String method;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "paid_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime paidAt;

    @Column(name = "cancelled_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime cancelledAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    private Payment(Long orderId, String orderNo, long amount) {
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.portonePaymentId = PORTONE_ID_PREFIX + orderNo;
        this.amount = amount;
        this.status = PaymentStatus.READY;
    }

    /** 결제 준비 — 포트원 paymentId 는 orderNo 에서 정해져 재요청에도 같다 */
    public static Payment ready(Long orderId, String orderNo, long amount) {
        return new Payment(orderId, orderNo, amount);
    }

    public boolean isPaid() {
        return status == PaymentStatus.PAID;
    }

    public long remainingAmount() {
        return amount - cancelledAmount;
    }

    /** 포트원 PAID + 금액 일치 확인 후 */
    public void markPaid(String pgTxId, String method, LocalDateTime paidAt) {
        requireStatus(PaymentStatus.READY);
        this.status = PaymentStatus.PAID;
        this.pgTxId = pgTxId;
        this.method = method;
        this.paidAt = paidAt;
    }

    /** 결제가 성립하지 않음 — 금액 불일치(자동 취소 후) · 결제 대기 만료 · 주문 Saga 보상 */
    public void markFailed() {
        requireStatus(PaymentStatus.READY);
        this.status = PaymentStatus.FAILED;
    }

    /** 포트원 취소 성공 후 — 누적 취소액이 결제 금액이면 CANCELLED */
    public void applyCancel(long cancelAmount, LocalDateTime cancelledAt) {
        if (!status.isCancellable()) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_INVALID_STATUS);
        }
        if (cancelAmount < 1 || cancelAmount > remainingAmount()) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_CANCEL_AMOUNT_EXCEEDED);
        }
        this.cancelledAmount += cancelAmount;
        if (cancelledAmount == amount) {
            this.status = PaymentStatus.CANCELLED;
            this.cancelledAt = cancelledAt;
        } else {
            this.status = PaymentStatus.PARTIAL_CANCELLED;
        }
    }

    private void requireStatus(PaymentStatus expected) {
        if (status != expected) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_INVALID_STATUS);
        }
    }
}
