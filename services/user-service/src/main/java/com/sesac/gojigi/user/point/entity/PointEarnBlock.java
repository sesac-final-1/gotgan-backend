package com.sesac.gojigi.user.point.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 적립 제외 상품주문 — 취소 · 반품 환원 · 회수 때 남겨, 뒤늦게 도착한 OrderPaid 적립을 건너뛴다 (V5 point_earn_block). INSERT 전용.
 */
@Getter
@Entity
@Table(name = "point_earn_block")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointEarnBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "point_earn_block_id")
    private Long id;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    public PointEarnBlock(Long orderItemId) {
        this.orderItemId = orderItemId;
    }
}
