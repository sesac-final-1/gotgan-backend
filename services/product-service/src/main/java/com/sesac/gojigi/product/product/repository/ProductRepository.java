package com.sesac.gojigi.product.product.repository;

import com.sesac.gojigi.product.product.entity.Product;
import com.sesac.gojigi.product.product.entity.ProductStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /** "없거나 삭제된 상품 → 404 PRODUCT_NOT_FOUND" 공통 조회 (6-2 · 13 · 15 · 16절). findById 는 삭제된 상품도 읽는다 — 재고 복원 · 지난 주문용 */
    Optional<Product> findByIdAndDeletedAtIsNull(Long id);

    /** 스토어 상품 전체 (11절 · 6-1절) — 삭제만 빼고 상태 무관, 등록일 최신 순 */
    List<Product> findByStoreIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(Long storeId);

    /** 상품별 재고 합계 — 삭제 안 된 옵션만. 결과 [productId, 합계] (옵션 · 이미지 컬렉션을 상품마다 따로 읽지 않으려고 묶어서) */
    @Query("""
            select o.product.id, coalesce(sum(o.stock), 0) from ProductOption o
            where o.product.id in :productIds and o.deletedAt is null
            group by o.product.id""")
    List<Object[]> sumStockByProductIds(@Param("productIds") Collection<Long> productIds);

    /** 대표 이미지 키 (sortOrder 0) — 결과 [productId, s3Key] */
    @Query("select i.product.id, i.s3Key from ProductImage i where i.product.id in :productIds and i.sortOrder = 0")
    List<Object[]> findMainImageKeys(@Param("productIds") Collection<Long> productIds);

    /** 통계 대상 — 삭제 안 된 ON_SALE 상품의 [productId, name, 재고 합계]. storeId 가 null 이면 전체 (5절) */
    @Query("""
            select p.id, p.name, coalesce(sum(o.stock), 0) from Product p
            left join p.options o on o.deletedAt is null
            where p.deletedAt is null and p.status = com.sesac.gojigi.product.product.entity.ProductStatus.ON_SALE
              and (:storeId is null or p.storeId = :storeId)
            group by p.id, p.name""")
    List<Object[]> findOnSaleStocks(@Param("storeId") Long storeId);

    /** 상태별 상품 수 — 삭제 안 된 것만. storeId 가 null 이면 전체 */
    @Query("""
            select count(p) from Product p
            where p.deletedAt is null and p.status in :statuses and (:storeId is null or p.storeId = :storeId)""")
    long countByStatuses(@Param("storeId") Long storeId, @Param("statuses") Collection<ProductStatus> statuses);
}
