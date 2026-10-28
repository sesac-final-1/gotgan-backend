package com.sesac.gojigi.store.common.event;

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

/** 이벤트 아웃박스 행 (storedb outbox_event, V4) — 보낼 봉투의 재료. 보낸 뒤에도 지우지 않는다 */
@Getter
@Entity
@Table(name = "outbox_event")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    public enum Status { NEW, SENT }

    /** last_error VARCHAR(200) */
    private static final int ERROR_MAX = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "outbox_event_id")
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    /** 스토어 ID 또는 입점 신청 ID */
    @Column(name = "aggregate_id", nullable = false, length = 40)
    private String aggregateId;

    @Column(name = "payload", nullable = false, columnDefinition = "JSON")
    private String payload;

    /** 봉투 occurredAt — 업무 시각(앱 Clock), 다시 보내도 같다 */
    @Column(name = "occurred_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
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

    public static OutboxEvent of(String eventId, String eventType, String aggregateId, String payload,
            LocalDateTime occurredAt) {
        OutboxEvent row = new OutboxEvent();
        row.eventId = eventId;
        row.eventType = eventType;
        row.aggregateId = aggregateId;
        row.payload = payload;
        row.occurredAt = occurredAt;
        row.status = Status.NEW;
        return row;
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
