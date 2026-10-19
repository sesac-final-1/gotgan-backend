package com.sesac.gojigi.settlement.settlement.entity;

import com.sesac.gojigi.settlement.common.entity.BaseTimeEntity;
import com.sesac.gojigi.settlement.settlement.exception.SettlementErrorCode;
import com.sesac.gojigi.settlement.settlement.exception.SettlementException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주간 정산서 — 스토어 × 주차 1건 (uk_settlement_store_week). 금액은 연결된 settlement_item 합계.
 * 상태 전이는 이 엔티티 메서드로만 하고, 바뀔 때마다 서비스가 settlement_history 를 남긴다.
 */
@Getter
@Entity
@Table(name = "settlement")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "settlement_id")
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Column(name = "week_end", nullable = false)
    private LocalDate weekEnd;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "sales_amt", nullable = false)
    private long salesAmt;

    @Column(name = "commission_amt", nullable = false)
    private long commissionAmt;

    @Column(name = "payment_fee_amt", nullable = false)
    private long paymentFeeAmt;

    @Column(name = "payout_amt", nullable = false)
    private long payoutAmt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SettlementStatus status;

    @Column(name = "hold_reason")
    private String holdReason;

    @Column(name = "scheduled_pay_date", nullable = false)
    private LocalDate scheduledPayDate;

    @Column(name = "held_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime heldAt;

    @Column(name = "paid_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime paidAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    private Settlement(Long storeId, LocalDate weekStart, LocalDate weekEnd, LocalDate scheduledPayDate) {
        this.storeId = storeId;
        this.weekStart = weekStart;
        this.weekEnd = weekEnd;
        this.scheduledPayDate = scheduledPayDate;
        this.status = SettlementStatus.SCHEDULED;
    }

    public static Settlement create(Long storeId, LocalDate weekStart, LocalDate weekEnd, LocalDate scheduledPayDate) {
        return new Settlement(storeId, weekStart, weekEnd, scheduledPayDate);
    }

    /** 연결된 상품주문 합계로 금액을 다시 계산 — 집계 · 늦게 들어온 구매확정 반영 */
    public void recalculate(List<SettlementItem> items) {
        if (status == SettlementStatus.PAID) {
            throw new SettlementException(SettlementErrorCode.SETTLE_INVALID_STATUS);
        }
        this.itemCount = items.size();
        this.salesAmt = items.stream().mapToLong(SettlementItem::getItemAmt).sum();
        this.commissionAmt = items.stream().mapToLong(SettlementItem::getCommissionAmt).sum();
        this.paymentFeeAmt = items.stream().mapToLong(SettlementItem::getPaymentFeeAmt).sum();
        this.payoutAmt = salesAmt - commissionAmt - paymentFeeAmt;
    }

    public void hold(String reason, LocalDateTime at) {
        if (status != SettlementStatus.SCHEDULED) {
            throw new SettlementException(SettlementErrorCode.SETTLE_INVALID_STATUS);
        }
        this.status = SettlementStatus.HELD;
        this.holdReason = reason;
        this.heldAt = at;
    }

    public void release() {
        if (status != SettlementStatus.HELD) {
            throw new SettlementException(SettlementErrorCode.SETTLE_INVALID_STATUS);
        }
        this.status = SettlementStatus.SCHEDULED;
        this.holdReason = null;
        this.heldAt = null;
    }

    public void markPaid(LocalDateTime at) {
        if (status != SettlementStatus.SCHEDULED) {
            throw new SettlementException(SettlementErrorCode.SETTLE_INVALID_STATUS);
        }
        this.status = SettlementStatus.PAID;
        this.paidAt = at;
    }
}
