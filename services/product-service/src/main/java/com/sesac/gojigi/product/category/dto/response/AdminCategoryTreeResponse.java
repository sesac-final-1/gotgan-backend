package com.sesac.gojigi.product.category.dto.response;

import com.sesac.gojigi.product.category.entity.Category;
import java.util.List;
import java.util.Map;

/**
 * 관리자 트리 (GET /internal/categories) — 비활성 포함, 상위 · 하위 모두 sortOrder 순.
 * productCount 는 삭제 안 된 상품 수(상태 무관) — 하위는 그 카테고리, 상위는 하위 합, 없으면 0 (admin 카테고리 수수료 화면의 영향 상품 수). 필드는 추가만 한다
 */
public record AdminCategoryTreeResponse(Long id, String name, String nameEn, int sortOrder, boolean active, List<Child> children,
        long productCount) {

    public record Child(Long id, String name, String nameEn, int sortOrder, boolean active, long productCount) {

        static Child of(Category c, long productCount) {
            return new Child(c.getId(), c.getName(), c.getNameEn(), c.getSortOrder(), c.isActive(), productCount);
        }
    }

    /** productCountByCategory 는 하위 카테고리 id → 상품 수 (상품은 하위 id 만 가진다) */
    public static AdminCategoryTreeResponse of(Category top, List<Category> children, Map<Long, Long> productCountByCategory) {
        List<Child> childItems = children.stream()
                .map(c -> Child.of(c, productCountByCategory.getOrDefault(c.getId(), 0L)))
                .toList();
        long total = childItems.stream().mapToLong(Child::productCount).sum();
        return new AdminCategoryTreeResponse(top.getId(), top.getName(), top.getNameEn(), top.getSortOrder(), top.isActive(),
                childItems, total);
    }
}
