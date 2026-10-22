package com.sesac.gojigi.order.order.entity;

import com.sesac.gojigi.order.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 주문의 스토어별 묶음배송비 — 같은 스토어 상품끼리 배송비 1회, 주문 · 스토어당 1행 */
@Getter
@Entity
@Table(name = "order_store_shipment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderStoreShipment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_store_shipment_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** store-service store.store_id (논리 참조) */
    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "shipping_fee", nullable = false)
    private long shippingFee;

    private OrderStoreShipment(Order order, Long storeId, long shippingFee) {
        this.order = order;
        this.storeId = storeId;
        this.shippingFee = shippingFee;
    }

    public static OrderStoreShipment of(Order order, Long storeId, long shippingFee) {
        return new OrderStoreShipment(order, storeId, shippingFee);
    }
}
