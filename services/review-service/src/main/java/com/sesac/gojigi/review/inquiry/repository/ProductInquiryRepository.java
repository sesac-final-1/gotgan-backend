package com.sesac.gojigi.review.inquiry.repository;

import com.sesac.gojigi.review.inquiry.entity.ProductInquiry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductInquiryRepository extends JpaRepository<ProductInquiry, Long> {
}
