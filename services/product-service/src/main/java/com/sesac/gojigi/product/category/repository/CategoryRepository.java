package com.sesac.gojigi.product.category.repository;

import com.sesac.gojigi.product.category.entity.Category;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** 트리 조립용 — 수십 건이라 한 번에 읽어 메모리에서 묶는다 */
    List<Category> findAllByOrderBySortOrderAscIdAsc();

    /** 같은 부모(상위끼리는 parentId null) 아래 같은 이름 — excludeId 는 수정 때 자기 자신 제외 (생성은 null) */
    @Query("""
            select count(c) > 0 from Category c
            where ((:parentId is null and c.parentId is null) or c.parentId = :parentId)
              and c.name = :name and (:excludeId is null or c.id <> :excludeId)
            """)
    boolean existsSiblingName(@Param("parentId") Long parentId, @Param("name") String name, @Param("excludeId") Long excludeId);

    /** sortOrder 생략 시 같은 부모 안 맨 뒤 = 지금 최대값 + 1 (형제가 없으면 1) */
    @Query("""
            select coalesce(max(c.sortOrder), 0) + 1 from Category c
            where (:parentId is null and c.parentId is null) or c.parentId = :parentId
            """)
    int nextSortOrder(@Param("parentId") Long parentId);
}
