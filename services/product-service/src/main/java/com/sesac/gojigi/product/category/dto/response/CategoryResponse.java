package com.sesac.gojigi.product.category.dto.response;

import com.sesac.gojigi.product.category.entity.Category;

/** 카테고리 1건 — 생성 · 수정 · 사용 여부 변경 응답 */
public record CategoryResponse(Long id, Long parentId, String name, String nameEn, int sortOrder, boolean active) {

    public static CategoryResponse from(Category c) {
        return new CategoryResponse(c.getId(), c.getParentId(), c.getName(), c.getNameEn(), c.getSortOrder(), c.isActive());
    }
}
