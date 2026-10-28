package com.sesac.gojigi.review.review.repository;

import com.sesac.gojigi.review.review.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<Review, Long> {
}
