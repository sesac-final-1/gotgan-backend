package com.sesac.gojigi.review.review.repository;

import com.sesac.gojigi.review.review.entity.Review;
import com.sesac.gojigi.review.review.entity.ReviewImage;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ReviewImageRepository extends JpaRepository<ReviewImage, Long> {

    /** 여러 리뷰의 사진을 한 번에 — 리뷰 · 순서대로 (목록 응답의 N+1 방지) */
    @Query("select i from ReviewImage i where i.review.id in :reviewIds order by i.review.id, i.sortOrder")
    List<ReviewImage> findByReviewIdsOrdered(Collection<Long> reviewIds);

    /** 수정 때 사진 교체 — 새 순서를 넣기 전에 DB 에서 지워야 uk(review_id, sort_order)에 걸리지 않는다 */
    @Modifying(flushAutomatically = true)
    @Query("delete from ReviewImage i where i.review = :review")
    void deleteByReview(Review review);
}
