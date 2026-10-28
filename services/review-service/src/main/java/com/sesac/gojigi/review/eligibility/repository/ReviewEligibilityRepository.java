package com.sesac.gojigi.review.eligibility.repository;

import com.sesac.gojigi.review.eligibility.entity.ReviewEligibility;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewEligibilityRepository extends JpaRepository<ReviewEligibility, Long> {

    Optional<ReviewEligibility> findByOrderItemId(Long orderItemId);
}
