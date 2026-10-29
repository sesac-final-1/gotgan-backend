package com.sesac.gojigi.review.inquiry.client.dto;

import java.util.List;

/** product 상품 상세 — GET /api/products/{id} 응답 중 문의 스냅샷에 쓰는 값만 (모르는 필드는 무시). images 첫 번째가 대표 */
public record ProductSummaryResponse(Long productId, Long storeId, String name, List<String> images) {

    public String thumbnailUrl() {
        return images == null || images.isEmpty() ? null : images.getFirst();
    }
}
