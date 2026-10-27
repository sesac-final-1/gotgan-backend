package com.sesac.gojigi.admin.product.controller;

import com.sesac.gojigi.admin.product.client.AdminProductClientAdapter;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 관리자 상품 (PRD-13) — product /internal/products (docs/api/product.md 6절) 응답 · 오류 그대로. 화면은 스토어 상세의 상품 표 */
@Tag(name = "관리자 상품", description = "product 내부 API 를 그대로 전달 — 인가: ADMIN")
@RestController
@RequiredArgsConstructor
public class AdminProductController {

    private final AdminProductClientAdapter adminProductClientAdapter;

    @Operation(summary = "스토어 상품 목록", description = """
            판매자 상품 목록과 같은 응답 [{productId, name, categoryName, topCategoryName, price, salePrice, imageUrl, totalStock, soldOut,
            status, statusReason, displayable, hiddenReason, createdAt}] — 전체(페이징 없음) · 상태 무관 · 삭제 제외, 등록일 최신 순""")
    @GetMapping("/api/admin/stores/{storeId}/products")
    public ApiResponse<JsonNode> products(@PathVariable Long storeId) {
        return ApiResponse.success("스토어 상품을 조회했습니다.", adminProductClientAdapter.products(storeId));
    }

    @Operation(summary = "강제 판매중지 · 해제", description = """
            {blocked, reason(blocked=true 면 필수)} → {productId, status, statusReason}. 해제하면 STOPPED(판매자가 점검 후 재개).
            에러: 400 PRODUCT_BLOCK_REASON_REQUIRED · 404 PRODUCT_NOT_FOUND""")
    @PatchMapping("/api/admin/products/{productId}/block")
    public ApiResponse<JsonNode> block(@PathVariable Long productId, @RequestBody JsonNode body) {
        return ApiResponse.success("상품 판매 상태를 변경했습니다.", adminProductClientAdapter.block(productId, body));
    }
}
