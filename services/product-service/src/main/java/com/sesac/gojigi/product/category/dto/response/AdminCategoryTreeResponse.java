package com.sesac.gojigi.product.category.dto.response;

import com.sesac.gojigi.product.category.entity.Category;
import java.util.List;

/** 관리자 트리 (GET /internal/categories) — 비활성 포함, 상위 · 하위 모두 sortOrder 순 */
public record AdminCategoryTreeResponse(Long id, String name, String nameEn, int sortOrder, boolean active, List<Child> children) {

    public record Child(Long id, String name, String nameEn, int sortOrder, boolean active) {

        static Child from(Category c) {
            return new Child(c.getId(), c.getName(), c.getNameEn(), c.getSortOrder(), c.isActive());
        }
    }

    public static AdminCategoryTreeResponse of(Category top, List<Category> children) {
        return new AdminCategoryTreeResponse(top.getId(), top.getName(), top.getNameEn(), top.getSortOrder(), top.isActive(),
                children.stream().map(Child::from).toList());
    }
}
