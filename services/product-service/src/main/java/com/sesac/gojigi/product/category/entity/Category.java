package com.sesac.gojigi.product.category.entity;

import com.sesac.gojigi.product.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 카테고리 (상위/하위 2단계, CAT-01 · 02). 삭제하지 않는다 — store 희망 카테고리 · settlement 수수료가 category_id 를 논리 참조.
 * 상위는 사용 중지(active=false)로 만들고 수수료 등록 뒤 켠다(10/20 결정). 부모는 바꿀 수 없다(수수료 기준이 조용히 바뀜 — product.md 결정 11).
 */
@Getter
@Entity
@Table(name = "category")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "category_id")
    private Long id;

    /** 상위는 null */
    @Column(name = "parent_id", updatable = false)
    private Long parentId;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "name_en", length = 50)
    private String nameEn;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    private Category(Long parentId, String name, String nameEn, int sortOrder, boolean active) {
        this.parentId = parentId;
        this.name = name;
        this.nameEn = nameEn;
        this.sortOrder = sortOrder;
        this.active = active;
    }

    /** 상위 — 사용 중지로 생성 (관리자가 수수료를 등록한 뒤 켠다) */
    public static Category top(String name, String nameEn, int sortOrder) {
        return new Category(null, name, nameEn, sortOrder, false);
    }

    /** 하위 — 사용 중으로 생성 (상위가 꺼져 있으면 함께 숨겨진다) */
    public static Category child(Long parentId, String name, String nameEn, int sortOrder) {
        return new Category(parentId, name, nameEn, sortOrder, true);
    }

    public boolean isTop() {
        return parentId == null;
    }

    public void update(String name, String nameEn, int sortOrder) {
        this.name = name;
        this.nameEn = nameEn;
        this.sortOrder = sortOrder;
    }

    public void changeActive(boolean active) {
        this.active = active;
    }
}
