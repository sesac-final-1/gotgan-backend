package com.sesac.gojigi.review.review.repository;

import com.sesac.gojigi.review.review.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    /** 삭제된 리뷰도 포함 — uk_review_order_item 이라 삭제 후 다시 쓸 수 없다 */
    boolean existsByOrderItemId(Long orderItemId);
}
