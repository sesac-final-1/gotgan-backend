package com.sesac.gojigi.user.point.entity;

import com.sesac.gojigi.user.point.exception.PointErrorCode;
import com.sesac.gojigi.user.point.exception.PointException;
import com.sesac.gojigi.user.user.entity.User;
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

/**
 * 포인트 적립 · 사용 · 환원 · 회수 이력 — INSERT 전용 (수정 · 삭제 없음, created_at 만).
 * 잔액 변경(users.point_balance 조건부 UPDATE)과 같은 트랜잭션에서 저장한다.
 * 멱등: (type, ref_key) 유니크 — USE · 보상 RESTORE: sagaId / 클레임 RESTORE · RECLAIM: claim-{claimId}-{orderItemId} / EARN: {eventId}-{orderItemId}
 */
@Getter
@Entity
@Table(name = "point_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "point_history_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** order-service order_item.order_item_id (논리 참조) — USE 는 NULL */
    @Column(name = "order_item_id")
    private Long orderItemId;

    /** order-service orders.order_no (논리 참조) — 이력 화면 표시용 */
    @Column(name = "order_no", length = 20)
    private String orderNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private PointType type;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "shortfall_amt", nullable = false)
    private long shortfallAmt;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "ref_key", nullable = false, length = 64)
    private String refKey;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    private PointHistory(User user, Long orderItemId, String orderNo, PointType type,
                         long amount, long shortfallAmt, long balanceAfter, String refKey) {
        if (amount <= 0 || shortfallAmt < 0 || balanceAfter < 0) {
            throw new PointException(PointErrorCode.POINT_INVALID_AMOUNT);
        }
        this.user = user;
        this.orderItemId = orderItemId;
        this.orderNo = orderNo;
        this.type = type;
        this.amount = amount;
        this.shortfallAmt = shortfallAmt;
        this.balanceAfter = balanceAfter;
        this.refKey = refKey;
    }

    /** 구매확정 적립 — refKey {eventId}-{orderItemId} */
    public static PointHistory earn(User user, Long orderItemId, String orderNo, long amount, long balanceAfter, String refKey) {
        return new PointHistory(user, orderItemId, orderNo, PointType.EARN, amount, 0L, balanceAfter, refKey);
    }

    /** 주문 결제 사용 — 주문 단위라 orderItemId 없음, refKey sagaId */
    public static PointHistory use(User user, String orderNo, long amount, long balanceAfter, String refKey) {
        return new PointHistory(user, null, orderNo, PointType.USE, amount, 0L, balanceAfter, refKey);
    }

    /** 사용 환원 — Saga 보상(refKey sagaId) · 클레임(refKey claim-{claimId}-{orderItemId}) */
    public static PointHistory restore(User user, Long orderItemId, String orderNo, long amount, long balanceAfter, String refKey) {
        return new PointHistory(user, orderItemId, orderNo, PointType.RESTORE, amount, 0L, balanceAfter, refKey);
    }

    /** 적립 회수 — 잔액이 모자라면 0 까지만 회수하고 못 받은 차액을 shortfallAmt 로 남긴다 */
    public static PointHistory reclaim(User user, Long orderItemId, String orderNo, long amount, long shortfallAmt,
                                       long balanceAfter, String refKey) {
        return new PointHistory(user, orderItemId, orderNo, PointType.RECLAIM, amount, shortfallAmt, balanceAfter, refKey);
    }
}
