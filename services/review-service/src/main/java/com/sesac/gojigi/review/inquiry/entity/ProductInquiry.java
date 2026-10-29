package com.sesac.gojigi.review.inquiry.entity;

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
 * 상품문의 · 판매자 답변 (REV-07~09). 소프트 삭제. 비밀글은 작성자 · 해당 스토어 판매자만 본문 확인.
 * 본문 · 답변은 서비스 계층에서 sanitizer 로 정화한 값이 들어온다.
 */
@Getter
@Entity
@Table(name = "product_inquiry")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductInquiry extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_inquiry_id")
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private Long storeId;

    /** 등록 시점 스냅샷 (V6) — 조회 화면은 product · user 를 다시 부르지 않는다 */
    @Column(name = "product_name", nullable = false, length = 200, updatable = false)
    private String productName;

    @Column(name = "thumbnail_url", length = 500, updatable = false)
    private String thumbnailUrl;

    @Column(name = "writer_display_name", nullable = false, length = 50, updatable = false)
    private String writerDisplayName;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "answer_content", columnDefinition = "TEXT")
    private String answerContent;

    @Column(name = "is_secret", nullable = false)
    private boolean secret;

    @Column(name = "answered_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime answeredAt;

    @Column(name = "deleted_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime deletedAt;

    @Builder
    private ProductInquiry(Long userId, Long productId, Long storeId, String productName, String thumbnailUrl,
            String writerDisplayName, String content, boolean secret) {
        this.userId = userId;
        this.productId = productId;
        this.storeId = storeId;
        this.productName = productName;
        this.thumbnailUrl = thumbnailUrl;
        this.writerDisplayName = writerDisplayName;
        this.content = content;
        this.secret = secret;
    }

    /** 작성자 수정 — 본문 · 비밀글 여부만 (답변 전에만, 서비스가 검사) */
    public void edit(String content, boolean secret) {
        this.content = content;
        this.secret = secret;
    }

    /** 판매자 답변 작성 · 수정 — 1개라 덮어쓴다 */
    public void answer(String answerContent, LocalDateTime answeredAt) {
        this.answerContent = answerContent;
        this.answeredAt = answeredAt;
    }

    /** 물리 DELETE 금지 */
    public void softDelete(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isAnswered() {
        return answeredAt != null;
    }

    /** 소유권 검사 — 다른 회원의 문의 삭제 차단 */
    public boolean isOwnedBy(Long userId) {
        return Objects.equals(this.userId, userId);
    }

    /** 판매자 소유권 검사 — 다른 스토어 문의에 답변 차단 */
    public boolean belongsToStore(Long storeId) {
        return Objects.equals(this.storeId, storeId);
    }

    /** 본문을 볼 수 있는지 — 공개글은 누구나, 비밀글은 작성자 · 해당 스토어 판매자만 (비로그인은 null) */
    public boolean isReadableBy(Long viewerUserId, Long viewerStoreId) {
        return !secret || isOwnedBy(viewerUserId) || belongsToStore(viewerStoreId);
    }
}
