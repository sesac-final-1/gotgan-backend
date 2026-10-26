package com.sesac.gojigi.order.claim.entity;

import com.sesac.gojigi.order.claim.exception.ClaimErrorCode;
import com.sesac.gojigi.order.claim.exception.ClaimException;
import com.sesac.gojigi.order.common.entity.BaseTimeEntity;
import com.sesac.gojigi.order.order.entity.OrderItem;
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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 취소 · 반품 · 교환 요청 — claim → order 단방향 (컨벤션 2-1). 상품주문 status 와 한 트랜잭션으로 바꾼다.
 * refundAmt 는 교환이면 NULL (OrderItemCancelled · Returned.refundAmt). 클레임 1건 = 상품주문 1건.
 */
@Getter
@Entity
@Table(name = "order_claim")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderClaim extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_claim_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;

    /** user-service users.user_id (논리 참조) — 요청한 구매자 */
    @Column(name = "requester_id", nullable = false)
    private Long requesterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "claim_type", nullable = false, length = 20)
    private ClaimType claimType;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 30)
    private ClaimReasonCode reasonCode;

    @Column(name = "reason_detail", length = 500)
    private String reasonDetail;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @Column(name = "refund_amt")
    private Long refundAmt;

    /** 돌려줄 사용 포인트 — 상품주문 사용 포인트 + 현금으로 돌려주지 못한 배송비 몫. 교환이면 NULL (V5) */
    @Column(name = "restore_point")
    private Long restorePoint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ClaimStatus status;

    @Column(name = "requested_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime requestedAt;

    /** 승인 · 거부 · 완료 처리 시각 */
    @Column(name = "processed_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime processedAt;

    @Builder
    private OrderClaim(OrderItem orderItem, Long requesterId, ClaimType claimType, ClaimReasonCode reasonCode,
                       String reasonDetail, Long refundAmt, Long restorePoint, LocalDateTime requestedAt) {
        this.orderItem = orderItem;
        this.requesterId = requesterId;
        this.claimType = claimType;
        this.reasonCode = reasonCode;
        this.reasonDetail = reasonDetail;
        this.refundAmt = refundAmt;
        this.restorePoint = restorePoint;
        this.requestedAt = requestedAt;
        this.status = ClaimStatus.REQUESTED;
    }

    /** 승인 — 결제완료 단계 취소는 접수와 동시에 자동 승인 (스펙 05장 클레임 상태표) */
    public void approve(LocalDateTime now) {
        transition(ClaimStatus.REQUESTED, ClaimStatus.APPROVED, now);
    }

    /** 완료 — 환불 Saga(결제 취소 · 재고 · 포인트)가 끝남 */
    public void complete(LocalDateTime now) {
        transition(ClaimStatus.APPROVED, ClaimStatus.COMPLETED, now);
    }

    private void transition(ClaimStatus from, ClaimStatus to, LocalDateTime now) {
        if (status != from) {
            throw new ClaimException(ClaimErrorCode.CLAIM_INVALID_STATUS);
        }
        this.status = to;
        this.processedAt = now;
    }
}
