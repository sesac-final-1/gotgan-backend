package com.sesac.gojigi.product.option.repository;

import com.sesac.gojigi.product.option.entity.ProductOption;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductOptionRepository extends JpaRepository<ProductOption, Long> {

    /** 가격 · 구매 가능 조회 (4절) — 삭제된 상품 · 옵션도 포함(호출자가 purchasable=false 로 받음). 상품은 한 번에 같이 읽는다 */
    @Query("select o from ProductOption o join fetch o.product where o.id in :ids")
    List<ProductOption> findWithProductByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * 재고 예약 · 해제의 첫 쿼리 — 옵션 행을 ID 오름차순으로 잠근다(SELECT … FOR UPDATE).
     * 같은 옵션을 건드리는 예약 · 해제 · 같은 sagaId 재요청이 줄을 서고, 순서가 같아 데드락이 나지 않는다 (product.md 1 · 2절).
     * 삭제된 옵션도 잠근다 — 해제는 삭제돼도 재고를 돌려놓는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from ProductOption o where o.id in :ids order by o.id")
    List<ProductOption> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);

    /** 조건부 차감 — 남은 재고가 모자라면 0행 (초과 판매 방지, product.md 1절). 잠금을 잡은 뒤라 경합은 없지만 마지막 방어선 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ProductOption o set o.stock = o.stock - :quantity where o.id = :id and o.stock >= :quantity")
    int decreaseStock(@Param("id") Long id, @Param("quantity") int quantity);

    /** 재고 되돌림 — 해제(2절) · 복원(3절). 옵션이 판매중지 · 삭제돼도 재고 수만 돌려놓는다 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ProductOption o set o.stock = o.stock + :quantity where o.id = :id")
    int increaseStock(@Param("id") Long id, @Param("quantity") int quantity);

    /** 판매자 재고 수정(14절) 대상 확인 — 이 상품의 삭제 안 된 조합 ID */
    @Query("select o.id from ProductOption o where o.product.id = :productId and o.deletedAt is null")
    List<Long> findLiveIdsByProductId(@Param("productId") Long productId);

    /**
     * 판매자 재고 수정 (14절) — 화면에서 본 재고(seenStock)와 지금 재고가 같을 때만 바꾼다. 0행이면 그 사이 주문이 바꾼 것(STOCK_CHANGED).
     * 행을 미리 잠그지 않아도 "본 값과 같을 때만" 조건이 덮어쓰기를 막는다. 재고 이력(stock_history)은 남기지 않는다(V1 DDL — Saga 멱등 전용).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ProductOption o set o.stock = :stock where o.id = :id and o.stock = :seenStock")
    int updateStockIfUnchanged(@Param("id") Long id, @Param("stock") int stock, @Param("seenStock") int seenStock);
}
