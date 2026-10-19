package com.sesac.gojigi.settlement.settlement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** 정산서 상태 이력 (INSERT 전용) — 관리자 정산 상세의 history[]. 관리자 ID 는 남기지 않음 (storedb 이력과 같은 규칙) */
@Getter
@Entity
@Table(name = "settlement_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementHistory {

    public enum Actor { SYSTEM, ADMIN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "settlement_history_id")
    private Long id;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private SettlementStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private SettlementStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 10)
    private Actor actorType;

    @Column(name = "reason")
    private String reason;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    private SettlementHistory(Long settlementId, SettlementStatus fromStatus, SettlementStatus toStatus, Actor actorType,
            String reason) {
        this.settlementId = settlementId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorType = actorType;
        this.reason = reason;
    }

    public static SettlementHistory of(Long settlementId, SettlementStatus from, SettlementStatus to, Actor actor, String reason) {
        return new SettlementHistory(settlementId, from, to, actor, reason);
    }
}
