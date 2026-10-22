package com.sesac.gojigi.order.order.entity;

import com.sesac.gojigi.order.common.entity.BaseTimeEntity;
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
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품주문 = 상태 관리 단위 (배송 · 구매확정 · 클레임). 상품 · 카테고리 · 스토어명은 주문 시점 스냅샷.
 * usedPoint 는 주문 사용 포인트를 금액 비율로 배분한 값(취소 · 반품 시 환원액),
 * earnedPoint 는 (itemAmt - usedPoint) 의 5% (취소 · 반품 시 회수액).
 */
@Getter
@Entity
@Table(name = "order_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** store-service store.store_id (논리 참조) — 판매자 주문 조회 핵심 키 */
    @Column(name = "store_id", nullable = false)
    private Long storeId;

    /** product-service product.product_id (논리 참조) */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    /** product-service product_option.product_option_id (논리 참조) */
    @Column(name = "product_option_id", nullable = false)
    private Long productOptionId;

    /** 하위 카테고리 스냅샷 (OrderItemConfirmed.categoryId) */
    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    /** 상위 카테고리 스냅샷 — 판매수수료 기준 (OrderItemConfirmed.topCategoryId) */
    @Column(name = "top_category_id", nullable = false)
    private Long topCategoryId;

    /** 외부 노출 상품주문번호 예: 20261021-000123-01 */
    @Column(name = "item_no", nullable = false, unique = true, length = 24)
    private String itemNo;

    @Column(name = "store_name", nullable = false, length = 100)
    private String storeName;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    /** 옵션 없는 상품이면 NULL */
    @Column(name = "option_name", length = 200)
    private String optionName;

    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "item_amt", nullable = false)
    private long itemAmt;

    @Column(name = "used_point", nullable = false)
    private long usedPoint;

    @Column(name = "earned_point", nullable = false)
    private long earnedPoint;

    @Column(name = "courier", length = 30)
    private String courier;

    @Column(name = "tracking_no", length = 40)
    private String trackingNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private OrderItemStatus status;

    @Column(name = "shipped_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime shippedAt;

    @Column(name = "delivered_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime deliveredAt;

    /** 구매확정 시각 — 정산 기준일 */
    @Column(name = "confirmed_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime confirmedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Builder
    private OrderItem(Order order, Long storeId, Long productId, Long productOptionId, Long categoryId,
                      Long topCategoryId, String itemNo, String storeName, String productName, String optionName,
                      String thumbnailUrl, long unitPrice, int quantity, long itemAmt, long usedPoint, long earnedPoint) {
        this.order = order;
        this.storeId = storeId;
        this.productId = productId;
        this.productOptionId = productOptionId;
        this.categoryId = categoryId;
        this.topCategoryId = topCategoryId;
        this.itemNo = itemNo;
        this.storeName = storeName;
        this.productName = productName;
        this.optionName = optionName;
        this.thumbnailUrl = thumbnailUrl;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.itemAmt = itemAmt;
        this.usedPoint = usedPoint;
        this.earnedPoint = earnedPoint;
        this.status = OrderItemStatus.PAYMENT_DONE;
    }
}
