package com.sesac.gojigi.review.review.entity;

import com.sesac.gojigi.review.common.entity.BaseTimeEntity;
import com.sesac.gojigi.review.eligibility.entity.ReviewEligibility;
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
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 리뷰 — 작성 자격(상품주문)당 1개, 판매자 답글 1개 (REV-02 · 05 · 06). review → review_eligibility 단방향, 소프트 삭제.
 * 블라인드는 관리자 조치(admin-service 가 내부 API 로 요청) — 상태의 주인은 review, 신고 · 조치 이력은 admindb (B안).
 * 본문 · 답글은 서비스 계층에서 sanitizer 로 정화한 값이 들어온다.
 */
@Getter
@Entity
@Table(name = "review")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "review_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_eligibility_id", nullable = false, updatable = false)
    private ReviewEligibility eligibility;

    @Column(name = "order_item_id", nullable = false, unique = true, updatable = false)
    private Long orderItemId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private Long storeId;

    @Column(name = "writer_display_name", nullable = false, length = 50, updatable = false)
    private String writerDisplayName;

    @Column(name = "rating", nullable = false, columnDefinition = "TINYINT UNSIGNED")
    private int rating;

    @Column(name = "title", length = 100)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "fit", length = 20)
    private ReviewFit fit;

    @Column(name = "helpful_count", nullable = false, columnDefinition = "INT UNSIGNED")
    private int helpfulCount;

    @Column(name = "reply_content", columnDefinition = "TEXT")
    private String replyContent;

    @Column(name = "is_blinded", nullable = false)
    private boolean blinded;

    @Column(name = "blind_reason")
    private String blindReason;

    @Column(name = "replied_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime repliedAt;

    @Column(name = "blinded_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime blindedAt;

    @Column(name = "deleted_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime deletedAt;

    private Review(ReviewEligibility eligibility, int rating, String title, String content, ReviewFit fit) {
        this.eligibility = eligibility;
        this.orderItemId = eligibility.getOrderItemId();
        this.userId = eligibility.getUserId();
        this.productId = eligibility.getProductId();
        this.storeId = eligibility.getStoreId();
        this.writerDisplayName = eligibility.getBuyerDisplayName();
        this.rating = rating;
        this.title = title;
        this.content = content;
        this.fit = fit;
    }

    /** 작성 — 상품주문 · 작성자 · 상품 · 스토어 · 표시 이름은 작성 자격에서 복사 (자격 · 기한 검사는 서비스 계층) */
    public static Review write(ReviewEligibility eligibility, int rating, String title, String content, ReviewFit fit) {
        return new Review(eligibility, rating, title, content, fit);
    }

    /** 본인 수정 (REV-05) — 사진은 review_image 에서 따로 */
    public void edit(int rating, String title, String content, ReviewFit fit) {
        this.rating = rating;
        this.title = title;
        this.content = content;
        this.fit = fit;
    }

    /** 판매자 답글 작성 · 수정 — 1개라 덮어쓴다 (블라인드된 리뷰는 답글 불가, 서비스 계층에서 검사) */
    public void reply(String replyContent, LocalDateTime repliedAt) {
        this.replyContent = replyContent;
        this.repliedAt = repliedAt;
    }

    /** 관리자 블라인드 — 이미 블라인드면 그대로 둔다 (같은 상태 재요청 멱등) */
    public void blind(String reason, LocalDateTime blindedAt) {
        if (blinded) {
            return;
        }
        this.blinded = true;
        this.blindReason = reason;
        this.blindedAt = blindedAt;
    }

    /** 블라인드 해제 — 사유 · 시각도 비운다 */
    public void unblind() {
        this.blinded = false;
        this.blindReason = null;
        this.blindedAt = null;
    }

    /** 물리 DELETE 금지 — 평균 · 분포 집계에서 빠진다 */
    public void softDelete(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean hasReply() {
        return replyContent != null;
    }

    /** 소유권 검사 — 다른 회원의 리뷰 수정 · 삭제 차단 */
    public boolean isOwnedBy(Long userId) {
        return Objects.equals(this.userId, userId);
    }

    /** 판매자 소유권 검사 — 다른 스토어 리뷰에 답글 차단 */
    public boolean belongsToStore(Long storeId) {
        return Objects.equals(this.storeId, storeId);
    }
}
