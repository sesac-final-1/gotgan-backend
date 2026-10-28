package com.sesac.gojigi.review.eligibility.entity;

import com.sesac.gojigi.review.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 리뷰 작성 자격 — OrderItemConfirmed 수신 시 상품주문당 1개 저장 (REV-01).
 * review 는 order · user 를 호출하지 않으므로 표시에 필요한 값(구매자 표시 이름 · 상품명)을 여기에 스냅샷한다.
 */
@Getter
@Entity
@Table(name = "review_eligibility")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewEligibility extends BaseTimeEntity {

    /** 작성 기한 — 구매확정 후 30일 (REV-02) */
    static final long WRITE_PERIOD_DAYS = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "review_eligibility_id")
    private Long id;

    @Column(name = "order_item_id", nullable = false, unique = true, updatable = false)
    private Long orderItemId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private Long storeId;

    @Column(name = "buyer_display_name", nullable = false, length = 50)
    private String buyerDisplayName;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "confirmed_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime confirmedAt;

    @Column(name = "expires_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime expiresAt;

    @Builder
    private ReviewEligibility(Long orderItemId, Long userId, Long productId, Long storeId,
                              String buyerDisplayName, String productName, LocalDateTime confirmedAt) {
        this.orderItemId = orderItemId;
        this.userId = userId;
        this.productId = productId;
        this.storeId = storeId;
        this.buyerDisplayName = buyerDisplayName;
        this.productName = productName;
        this.confirmedAt = confirmedAt;
        this.expiresAt = confirmedAt.plusDays(WRITE_PERIOD_DAYS);
    }

    /** 기한이 지났는지 — expires_at 시각까지는 작성 가능 */
    public boolean isExpired(LocalDateTime now) {
        return now.isAfter(expiresAt);
    }

    /** 소유권 검사 — 다른 회원의 자격으로 작성 차단 */
    public boolean isOwnedBy(Long userId) {
        return Objects.equals(this.userId, userId);
    }
}
