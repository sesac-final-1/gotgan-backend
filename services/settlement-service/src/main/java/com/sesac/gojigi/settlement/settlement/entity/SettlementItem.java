package com.sesac.gojigi.settlement.settlement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * 정산 대상 상품주문 — OrderItemConfirmed 1건 = 1행 (order_item_id UNIQUE). 수수료는 적재 시점 값으로 스냅샷하고 이후 바꾸지 않는다.
 * settlement_id 는 주간 집계 때 연결 (집계 전 NULL). INSERT 전용이라 created_at 만.
 */
@Getter
@Entity
@Table(name = "settlement_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "settlement_item_id")
    private Long id;

    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(name = "order_item_id", nullable = false, unique = true)
    private Long orderItemId;

    @Column(name = "order_no", nullable = false, length = 20)
    private String orderNo;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_name", nullable = false, length = 100)
    private String productName;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "item_amt", nullable = false)
    private long itemAmt;

    @Column(name = "commission_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal commissionRate;

    @Column(name = "commission_amt", nullable = false)
    private long commissionAmt;

    @Column(name = "payment_fee_amt", nullable = false)
    private long paymentFeeAmt;

    @Column(name = "confirmed_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime confirmedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    @Builder
    private SettlementItem(Long orderItemId, String orderNo, Long storeId, Long productId, String productName,
            Long categoryId, long itemAmt, BigDecimal commissionRate, long commissionAmt, long paymentFeeAmt,
            LocalDateTime confirmedAt) {
        this.orderItemId = orderItemId;
        this.orderNo = orderNo;
        this.storeId = storeId;
        this.productId = productId;
        this.productName = productName;
        this.categoryId = categoryId;
        this.itemAmt = itemAmt;
        this.commissionRate = commissionRate;
        this.commissionAmt = commissionAmt;
        this.paymentFeeAmt = paymentFeeAmt;
        this.confirmedAt = confirmedAt;
    }

    /** 판매자 지급액 = 매출 − 판매수수료 − 결제수수료 */
    public long payoutAmt() {
        return itemAmt - commissionAmt - paymentFeeAmt;
    }
}
