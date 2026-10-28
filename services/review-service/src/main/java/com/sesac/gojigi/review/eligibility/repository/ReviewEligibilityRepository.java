package com.sesac.gojigi.review.eligibility.repository;

import com.sesac.gojigi.review.eligibility.entity.ReviewEligibility;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReviewEligibilityRepository extends JpaRepository<ReviewEligibility, Long> {

    Optional<ReviewEligibility> findByOrderItemId(Long orderItemId);

    /**
     * 마이페이지 작성 가능한 리뷰 — 기한 안 · 리뷰가 없는(삭제한 리뷰 포함) 자격, 기한 임박순.
     * idx_review_eligibility_user_expires 범위 조회, 기한 30일이라 건수가 작아 페이징하지 않는다.
     */
    @Query("""
            select e from ReviewEligibility e
            where e.userId = :userId and e.expiresAt >= :now
              and not exists (select 1 from Review r where r.orderItemId = e.orderItemId)
            order by e.expiresAt asc, e.id asc
            """)
    List<ReviewEligibility> findWritable(Long userId, LocalDateTime now);
}
