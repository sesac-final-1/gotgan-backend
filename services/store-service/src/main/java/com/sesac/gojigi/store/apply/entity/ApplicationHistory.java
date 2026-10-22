package com.sesac.gojigi.store.apply.entity;

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

/** 입점 신청 상태 이력 (storedb application_history) — INSERT 전용이라 created_at 만 (STR-08 타임라인) */
@Getter
@Entity
@Table(name = "application_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApplicationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "application_history_id")
    private Long id;

    @Column(name = "store_application_id", nullable = false)
    private Long storeApplicationId;

    /** 최초 신청은 NULL */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private ApplicationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private ApplicationStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 10)
    private ActorType actorType;

    @Column(name = "reason")
    private String reason;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    public static ApplicationHistory of(Long storeApplicationId, ApplicationStatus fromStatus, ApplicationStatus toStatus,
            ActorType actorType, String reason) {
        ApplicationHistory history = new ApplicationHistory();
        history.storeApplicationId = storeApplicationId;
        history.fromStatus = fromStatus;
        history.toStatus = toStatus;
        history.actorType = actorType;
        history.reason = reason;
        return history;
    }
}
