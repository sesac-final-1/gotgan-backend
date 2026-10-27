package com.sesac.gojigi.product.product.repository;

import com.sesac.gojigi.product.product.entity.Product;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /** "없거나 삭제된 상품 → 404 PRODUCT_NOT_FOUND" 공통 조회 (6-2 · 13 · 15 · 16절). findById 는 삭제된 상품도 읽는다 — 재고 복원 · 지난 주문용 */
    Optional<Product> findByIdAndDeletedAtIsNull(Long id);
}
