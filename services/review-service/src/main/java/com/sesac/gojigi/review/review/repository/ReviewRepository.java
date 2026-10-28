package com.sesac.gojigi.review.review.repository;

import com.sesac.gojigi.review.review.entity.Review;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    /** 삭제된 리뷰도 포함 — uk_review_order_item 이라 삭제 후 다시 쓸 수 없다 */
    boolean existsByOrderItemId(Long orderItemId);

    /** 마이페이지 내가 쓴 리뷰 — 삭제 제외, 상품 표시값(자격 스냅샷)을 함께 읽는다 (idx_review_user_created) */
    @EntityGraph(attributePaths = "eligibility")
    Page<Review> findByUserIdAndDeletedAtIsNull(Long userId, Pageable pageable);

    /**
     * 판매자 리뷰 관리 목록 — 내 스토어 · 삭제 제외(블라인드 포함), 상품 · 탭 조건은 선택 (idx_review_store_created).
     * noReply: 답글이 없고 블라인드가 아닌 것(답글을 달 수 있는 것)만, lowRating: 별점 1~2.
     */
    @EntityGraph(attributePaths = "eligibility")
    @Query(value = """
            select r from Review r
            where r.storeId = :storeId and r.deletedAt is null
              and (:productId is null or r.productId = :productId)
              and (:noReply = false or (r.replyContent is null and r.blinded = false))
              and (:lowRating = false or r.rating <= 2)
            """,
            countQuery = """
            select count(r) from Review r
            where r.storeId = :storeId and r.deletedAt is null
              and (:productId is null or r.productId = :productId)
              and (:noReply = false or (r.replyContent is null and r.blinded = false))
              and (:lowRating = false or r.rating <= 2)
            """)
    Page<Review> findForSeller(Long storeId, Long productId, boolean noReply, boolean lowRating, Pageable pageable);

    /** 판매자 탭 숫자 — 전체(블라인드 포함) · 답글 미작성 · 1~2점, 목록 조건과 같다 */
    long countByStoreIdAndDeletedAtIsNull(Long storeId);

    long countByStoreIdAndDeletedAtIsNullAndReplyContentIsNullAndBlindedFalse(Long storeId);

    long countByStoreIdAndDeletedAtIsNullAndRatingLessThanEqual(Long storeId, int rating);

    /** 스토어 별점 분포 — 블라인드 · 삭제 제외 (구매자에게 보이는 리뷰 기준) → [rating, count] */
    @Query("""
            select r.rating, count(r) from Review r
            where r.storeId = :storeId and r.deletedAt is null and r.blinded = false
            group by r.rating
            """)
    List<Object[]> countByRatingForStore(Long storeId);

    /** 상품별 평균 별점 · 리뷰 수 — 블라인드 · 삭제 제외 → [productId, productName, avg, count] */
    @Query("""
            select r.productId, max(e.productName), avg(r.rating), count(r) from Review r join r.eligibility e
            where r.storeId = :storeId and r.deletedAt is null and r.blinded = false
            group by r.productId
            """)
    List<Object[]> summarizeByProductForStore(Long storeId);

    /** 상품 상세 공개 리뷰 — 블라인드 · 삭제 제외, 별점 하나만(선택), 옵션명은 자격 스냅샷 (idx_review_product_created · rating) */
    @EntityGraph(attributePaths = "eligibility")
    @Query(value = """
            select r from Review r
            where r.productId = :productId and r.deletedAt is null and r.blinded = false
              and (:rating is null or r.rating = :rating)
            """,
            countQuery = """
            select count(r) from Review r
            where r.productId = :productId and r.deletedAt is null and r.blinded = false
              and (:rating is null or r.rating = :rating)
            """)
    Page<Review> findForProduct(Long productId, Integer rating, Pageable pageable);

    /** 상품 별점 분포 — 블라인드 · 삭제 제외 → [rating, count] */
    @Query("""
            select r.rating, count(r) from Review r
            where r.productId = :productId and r.deletedAt is null and r.blinded = false
            group by r.rating
            """)
    List<Object[]> countByRatingForProduct(Long productId);

    /** 상품 사이즈 체감 분포 — 블라인드 · 삭제 · 체감 없음 제외 → [fit, count] */
    @Query("""
            select r.fit, count(r) from Review r
            where r.productId = :productId and r.deletedAt is null and r.blinded = false and r.fit is not null
            group by r.fit
            """)
    List<Object[]> countByFitForProduct(Long productId);

    /** 여러 상품 평균 별점 · 리뷰 수 — 블라인드 · 삭제 제외, 리뷰 없는 상품은 행이 없다 → [productId, avg, count] */
    @Query("""
            select r.productId, avg(r.rating), count(r) from Review r
            where r.productId in :productIds and r.deletedAt is null and r.blinded = false
            group by r.productId
            """)
    List<Object[]> summarizeByProductIds(Collection<Long> productIds);

    /** 여러 스토어 평균 별점 · 리뷰 수 — 블라인드 · 삭제 제외, 리뷰 없는 스토어는 행이 없다 → [storeId, avg, count] */
    @Query("""
            select r.storeId, avg(r.rating), count(r) from Review r
            where r.storeId in :storeIds and r.deletedAt is null and r.blinded = false
            group by r.storeId
            """)
    List<Object[]> summarizeByStoreIds(Collection<Long> storeIds);
}
