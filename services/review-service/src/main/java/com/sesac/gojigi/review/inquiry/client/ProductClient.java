package com.sesac.gojigi.review.inquiry.client;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.review.inquiry.client.dto.ProductSummaryResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;

/** product-service 상품 상세(공개 API를 클러스터 안에서 직접) — 문의 등록 때 스토어 · 상품명 · 대표 사진. 직접 쓰지 않고 InquirySnapshotReader 를 거친다 */
public interface ProductClient {

    @GetExchange("/api/products/{productId}")
    ApiResponse<ProductSummaryResponse> getProduct(@PathVariable Long productId);
}
