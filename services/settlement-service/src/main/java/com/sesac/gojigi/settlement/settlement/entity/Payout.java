package com.sesac.gojigi.settlement.settlement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** 지급 실행 기록 (INSERT 전용, 정산서당 1건 — uk_payout_settlement_id). 지급 시점의 정산 계좌를 스냅샷 */
@Getter
@Entity
@Table(name = "payout")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payout_id")
    private Long id;

    @Column(name = "settlement_id", nullable = false, unique = true)
    private Long settlementId;

    @Column(name = "payout_amt", nullable = false)
    private long payoutAmt;

    @Column(name = "bank_name", nullable = false, length = 50)
    private String bankName;

    @Column(name = "account_number", nullable = false, length = 50)
    private String accountNumber;

    @Column(name = "account_holder", nullable = false, length = 50)
    private String accountHolder;

    @Column(name = "paid_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime paidAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    private Payout(Long settlementId, long payoutAmt, String bankName, String accountNumber, String accountHolder,
            LocalDateTime paidAt) {
        this.settlementId = settlementId;
        this.payoutAmt = payoutAmt;
        this.bankName = bankName;
        this.accountNumber = accountNumber;
        this.accountHolder = accountHolder;
        this.paidAt = paidAt;
    }

    public static Payout of(Settlement settlement, SettlementStore store, LocalDateTime paidAt) {
        return new Payout(settlement.getId(), settlement.getPayoutAmt(), store.getBankName(), store.getAccountNumber(),
                store.getAccountHolder(), paidAt);
    }
}
