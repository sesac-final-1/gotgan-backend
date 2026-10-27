package com.sesac.gojigi.product.option.repository;

import com.sesac.gojigi.product.option.entity.ProductOption;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductOptionRepository extends JpaRepository<ProductOption, Long> {

    /** 가격 · 구매 가능 조회 (4절) — 삭제된 상품 · 옵션도 포함(호출자가 purchasable=false 로 받음). 상품은 한 번에 같이 읽는다 */
    @Query("select o from ProductOption o join fetch o.product where o.id in :ids")
    List<ProductOption> findWithProductByIdIn(@Param("ids") Collection<Long> ids);
}
