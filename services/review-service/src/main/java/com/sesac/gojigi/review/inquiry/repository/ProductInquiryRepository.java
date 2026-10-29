package com.sesac.gojigi.review.inquiry.repository;

import com.sesac.gojigi.review.inquiry.entity.ProductInquiry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProductInquiryRepository extends JpaRepository<ProductInquiry, Long> {

    /** 상품 상세 Q&A — 삭제 제외 (idx_product_inquiry_product_created) */
    Page<ProductInquiry> findByProductIdAndDeletedAtIsNull(Long productId, Pageable pageable);

    /** 마이페이지 내 문의 — 삭제 제외 (idx_product_inquiry_user_created) */
    Page<ProductInquiry> findByUserIdAndDeletedAtIsNull(Long userId, Pageable pageable);

    /** 판매자 문의 — 삭제 제외, 미답변 · 답변완료 탭 (idx_product_inquiry_store_answered) */
    @Query("""
            select i from ProductInquiry i
            where i.storeId = :storeId and i.deletedAt is null
              and (:unanswered = false or i.answeredAt is null)
              and (:answered = false or i.answeredAt is not null)
            """)
    Page<ProductInquiry> findForSeller(Long storeId, boolean unanswered, boolean answered, Pageable pageable);

    long countByStoreIdAndDeletedAtIsNull(Long storeId);

    long countByStoreIdAndAnsweredAtIsNullAndDeletedAtIsNull(Long storeId);
}
