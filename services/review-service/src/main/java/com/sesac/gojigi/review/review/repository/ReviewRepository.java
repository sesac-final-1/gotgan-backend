package com.sesac.gojigi.review.review.repository;

import com.sesac.gojigi.review.review.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    /** 삭제된 리뷰도 포함 — uk_review_order_item 이라 삭제 후 다시 쓸 수 없다 */
    boolean existsByOrderItemId(Long orderItemId);

    /** 마이페이지 내가 쓴 리뷰 — 삭제 제외, 상품 표시값(자격 스냅샷)을 함께 읽는다 (idx_review_user_created) */
    @EntityGraph(attributePaths = "eligibility")
    Page<Review> findByUserIdAndDeletedAtIsNull(Long userId, Pageable pageable);
}
