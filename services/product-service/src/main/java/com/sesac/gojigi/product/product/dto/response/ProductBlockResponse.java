package com.sesac.gojigi.product.product.dto.response;

import com.sesac.gojigi.product.product.entity.Product;
import com.sesac.gojigi.product.product.entity.ProductStatus;

/** 강제 판매중지 · 해제 결과 (6-2절) */
public record ProductBlockResponse(Long productId, ProductStatus status, String statusReason) {

    public static ProductBlockResponse from(Product product) {
        return new ProductBlockResponse(product.getId(), product.getStatus(), product.getStatusReason());
    }
}
