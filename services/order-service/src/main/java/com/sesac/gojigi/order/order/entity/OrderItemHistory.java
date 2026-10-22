package com.sesac.gojigi.order.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** 상품주문 상태 변경 이력 — INSERT 전용 (수정 · 삭제 없음, created_at 만). 상태 변경과 같은 트랜잭션에서 저장 */
@Getter
@Entity
@Table(name = "order_item_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItemHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_item_history_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;

    /** user-service users.user_id (논리 참조) — SYSTEM 이면 NULL */
    @Column(name = "actor_id")
    private Long actorId;

    /** 첫 상태 기록이면 NULL */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private OrderItemStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private OrderItemStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 10)
    private ActorType actorType;

    @Column(name = "reason", length = 300)
    private String reason;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    private OrderItemHistory(OrderItem orderItem, ActorType actorType, Long actorId,
                             OrderItemStatus fromStatus, OrderItemStatus toStatus, String reason) {
        this.orderItem = orderItem;
        this.actorType = actorType;
        this.actorId = actorType == ActorType.SYSTEM ? null : actorId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
    }

    public static OrderItemHistory of(OrderItem orderItem, ActorType actorType, Long actorId,
                                      OrderItemStatus fromStatus, OrderItemStatus toStatus, String reason) {
        return new OrderItemHistory(orderItem, actorType, actorId, fromStatus, toStatus, reason);
    }
}
