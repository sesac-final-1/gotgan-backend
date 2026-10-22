package com.sesac.gojigi.order.common.event;

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

/**
 * 보낼 이벤트 1건 (Transactional Outbox) — 업무 트랜잭션 안에서 저장하고, 커밋 후 SNS 로 보낸다.
 * eventId 는 봉투 eventId 그대로 — 소비자 processed_event 멱등 키. payload 는 JSON (docs/events.md).
 */
@Getter
@Entity
@Table(name = "outbox_event")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    public enum AggregateType { ORDER, ORDER_ITEM }

    public enum Status { NEW, SENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "outbox_event_id")
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "aggregate_type", nullable = false, length = 30)
    private AggregateType aggregateType;

    /** 주문번호 또는 상품주문번호 */
    @Column(name = "aggregate_id", nullable = false, length = 40)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "JSON")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status;

    @Column(name = "sent_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime sentAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    private OutboxEvent(String eventId, AggregateType aggregateType, String aggregateId, String eventType,
                        String payload) {
        this.eventId = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = Status.NEW;
    }

    public static OutboxEvent of(String eventId, AggregateType aggregateType, String aggregateId, String eventType,
                                 String payload) {
        return new OutboxEvent(eventId, aggregateType, aggregateId, eventType, payload);
    }
}
