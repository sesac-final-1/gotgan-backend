package com.sesac.gojigi.product.category.dto.response;

import com.sesac.gojigi.product.category.entity.Category;
import java.util.List;

/** 공개 트리 (GET /api/categories) — 사용 중인 것만, sortOrder 순으로 정렬해 주되 값은 넣지 않는다 */
public record CategoryTreeResponse(Long id, String name, String nameEn, List<Child> children) {

    public record Child(Long id, String name, String nameEn) {

        static Child from(Category c) {
            return new Child(c.getId(), c.getName(), c.getNameEn());
        }
    }

    public static CategoryTreeResponse of(Category top, List<Category> children) {
        return new CategoryTreeResponse(top.getId(), top.getName(), top.getNameEn(), children.stream().map(Child::from).toList());
    }
}
