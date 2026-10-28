package com.sesac.gojigi.store.store.entity;

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

/** 스토어 상태 이력 (storedb store_status_history) — INSERT 전용이라 created_at 만 (STR-14) */
@Getter
@Entity
@Table(name = "store_status_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_status_history_id")
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    /** 승인으로 생성될 때만 NULL */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private StoreStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private StoreStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 10)
    private StoreActorType actorType;

    @Column(name = "reason")
    private String reason;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    public static StoreStatusHistory of(Long storeId, StoreStatus fromStatus, StoreStatus toStatus,
            StoreActorType actorType, String reason) {
        StoreStatusHistory history = new StoreStatusHistory();
        history.storeId = storeId;
        history.fromStatus = fromStatus;
        history.toStatus = toStatus;
        history.actorType = actorType;
        history.reason = reason;
        return history;
    }
}
