package com.sesac.gojigi.settlement.common.event;

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

/** 보낼 이벤트 1건 — 업무 트랜잭션 안에서 저장하고, 커밋 후 SNS 로 보낸다 (V7, EventOutboxService) */
@Getter
@Entity
@Table(name = "event_outbox")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventOutbox {

    private static final int ERROR_MAX = 200;

    public enum Status { PENDING, SENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_outbox_id")
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 36, columnDefinition = "CHAR(36)")
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", length = ERROR_MAX)
    private String lastError;

    @Column(name = "sent_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime sentAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    private EventOutbox(String eventId, String eventType, String aggregateId, String payload) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.status = Status.PENDING;
    }

    public static EventOutbox pending(String eventId, String eventType, String aggregateId, String payload) {
        return new EventOutbox(eventId, eventType, aggregateId, payload);
    }

    public boolean isSent() {
        return status == Status.SENT;
    }

    public void markSent(LocalDateTime at) {
        this.status = Status.SENT;
        this.sentAt = at;
        this.attemptCount++;
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.attemptCount++;
        this.lastError = error == null || error.length() <= ERROR_MAX ? error : error.substring(0, ERROR_MAX);
    }
}
