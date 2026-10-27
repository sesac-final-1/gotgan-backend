package com.sesac.gojigi.product.image.entity;

import com.sesac.gojigi.product.common.entity.BaseTimeEntity;
import com.sesac.gojigi.product.product.entity.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 이미지 (productdb product_image). DB 에는 S3 키만 — 조회 URL 은 IMAGES_BASE_URL/{s3Key}.
 * 참조하는 곳이 없어 빠지면 행을 물리 삭제한다 (S3 객체는 남음 — 삭제 권한 없음).
 */
@Getter
@Entity
@Table(name = "product_image")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductImage extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_image_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    /** products/{storeId}/… 공개 경로 키 */
    @Column(name = "s3_key", nullable = false)
    private String s3Key;

    /** 0 이 대표 이미지 */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** 새 이미지 — 상품에는 Product.addImage 로 붙인다 */
    public static ProductImage of(String s3Key, int sortOrder) {
        ProductImage image = new ProductImage();
        image.s3Key = s3Key;
        image.sortOrder = sortOrder;
        return image;
    }

    /** Product.addImage 에서만 부른다 (양방향 연결) */
    public void assignProduct(Product product) {
        this.product = product;
    }
}
