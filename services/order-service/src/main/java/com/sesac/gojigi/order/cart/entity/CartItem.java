package com.sesac.gojigi.order.cart.entity;

import com.sesac.gojigi.order.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 장바구니 항목 — 원본은 MySQL, Redis Hash {@code cart:{userId}} 는 캐시 (10/13 결정 4 · 가이드라인 09장).
 * 쓰기는 MySQL 먼저 커밋 → Redis 갱신(실패는 WARN), 읽기는 Redis → 미스 · 장애 시 MySQL (서킷 {@code redisCart}).
 * 회원 · 옵션당 1행 ({@code uk_cart_item_user_option}).
 */
@Getter
@Entity
@Table(name = "cart_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartItem extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cart_item_id")
    private Long id;

    /** user-service users.user_id (논리 참조) */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** product-service product.product_id (논리 참조) */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    /** product-service product_option.product_option_id (논리 참조) */
    @Column(name = "product_option_id", nullable = false)
    private Long productOptionId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "checked", nullable = false)
    private boolean checked;

    @Builder
    private CartItem(Long userId, Long productId, Long productOptionId, int quantity) {
        this.userId = userId;
        this.productId = productId;
        this.productOptionId = productOptionId;
        this.quantity = quantity;
        this.checked = true;
    }
}
