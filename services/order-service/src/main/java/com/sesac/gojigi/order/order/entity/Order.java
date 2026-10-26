package com.sesac.gojigi.order.order.entity;

import com.sesac.gojigi.order.common.entity.BaseTimeEntity;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.exception.OrderException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 = 결제 단위 — 한 번 결제에 여러 스토어 상품이 섞일 수 있다. 테이블 orders (MySQL 키워드 order 회피).
 * 주문자 · 배송지는 주문 시점 스냅샷. ordererName(실명)은 알림 메일용이고 화면에는 ordererDisplayName 만 쓴다.
 * payAmt = totalProductAmt + totalShippingFee - usedPoint (포트원 결제창 금액).
 */
@Getter
@Entity
@Table(name = "orders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long id;

    /** user-service users.user_id (논리 참조) */
    @Column(name = "buyer_id", nullable = false)
    private Long buyerId;

    /** 외부 노출 주문번호 예: 20261021-000123 */
    @Column(name = "order_no", nullable = false, unique = true, length = 20)
    private String orderNo;

    @Column(name = "orderer_name", nullable = false, length = 50)
    private String ordererName;

    @Column(name = "orderer_display_name", nullable = false, length = 50)
    private String ordererDisplayName;

    @Column(name = "orderer_email", nullable = false, length = 100)
    private String ordererEmail;

    @Column(name = "receiver_name", nullable = false, length = 50)
    private String receiverName;

    @Column(name = "receiver_phone", nullable = false, length = 20)
    private String receiverPhone;

    @Column(name = "zipcode", nullable = false, length = 10)
    private String zipcode;

    @Column(name = "address1", nullable = false, length = 200)
    private String address1;

    @Column(name = "address2", length = 200)
    private String address2;

    @Column(name = "delivery_memo", length = 200)
    private String deliveryMemo;

    @Column(name = "total_product_amt", nullable = false)
    private long totalProductAmt;

    @Column(name = "total_shipping_fee", nullable = false)
    private long totalShippingFee;

    @Column(name = "used_point", nullable = false)
    private long usedPoint;

    @Column(name = "pay_amt", nullable = false)
    private long payAmt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "paid_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime paidAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Builder
    private Order(Long buyerId, String orderNo, String ordererName, String ordererDisplayName, String ordererEmail,
                  String receiverName, String receiverPhone, String zipcode, String address1, String address2,
                  String deliveryMemo, long totalProductAmt, long totalShippingFee, long usedPoint, long payAmt) {
        this.buyerId = buyerId;
        this.orderNo = orderNo;
        this.ordererName = ordererName;
        this.ordererDisplayName = ordererDisplayName;
        this.ordererEmail = ordererEmail;
        this.receiverName = receiverName;
        this.receiverPhone = receiverPhone;
        this.zipcode = zipcode;
        this.address1 = address1;
        this.address2 = address2;
        this.deliveryMemo = deliveryMemo;
        this.totalProductAmt = totalProductAmt;
        this.totalShippingFee = totalShippingFee;
        this.usedPoint = usedPoint;
        this.payAmt = payAmt;
        this.status = OrderStatus.PENDING;
    }

    /** 결제 완료 — 결제 검증 성공 또는 전액 포인트(0원) 주문 */
    public void markPaid(LocalDateTime paidAt) {
        requirePending();
        this.status = OrderStatus.PAID;
        this.paidAt = paidAt;
    }

    /** Saga 보상을 마친 주문 — 재고 부족 · 포인트 부족 · 결제 준비 실패 · 결제 실패 · 결제 대기 만료 */
    public void markFailed() {
        requirePending();
        this.status = OrderStatus.FAILED;
    }

    /** 주문 전체 취소 — 모든 상품주문이 취소 완료됨 (스펙 05장 "모든 상품주문이 취소되면 CANCELLED") */
    public void markCancelled() {
        if (status != OrderStatus.PAID) {
            throw new OrderException(OrderErrorCode.ORDER_INVALID_STATUS);
        }
        this.status = OrderStatus.CANCELLED;
    }

    private void requirePending() {
        if (status != OrderStatus.PENDING) {
            throw new OrderException(OrderErrorCode.ORDER_INVALID_STATUS);
        }
    }
}
