package com.sesac.gojigi.product.product.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.product.product.dto.request.ProductBlockRequest;
import com.sesac.gojigi.product.product.dto.response.ProductBlockResponse;
import com.sesac.gojigi.product.product.dto.response.ProductStatsResponse;
import com.sesac.gojigi.product.product.dto.response.SellerProductResponse;
import com.sesac.gojigi.product.product.service.AdminProductService;
import com.sesac.gojigi.product.product.service.ProductStatsService;
import com.sesac.gojigi.product.product.service.SellerProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 상품 조회 · 강제 판매중지 (PRD-13 · product.md 6절) · 상품 통계 (PRD-11 · 5절) — admin · store 가 호출 */
@Tag(name = "상품 (내부)", description = "admin-service · store-service 가 호출 — 인가: 내부")
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalProductController {

    private final AdminProductService adminProductService;
    private final SellerProductService sellerProductService;
    private final ProductStatsService productStatsService;

    @Operation(summary = "스토어 상품 목록 (관리자)", description = """
            판매자 상품 목록과 같은 응답 — 전체 조회(페이징 없음) · 상태 무관 · 삭제된 상품만 제외, 등록일 최신 순.
            에러: 400 INVALID_INPUT(storeId 없음)""")
    @GetMapping("/products")
    public ApiResponse<List<SellerProductResponse>> listByStore(@RequestParam Long storeId) {
        return ApiResponse.success("스토어 상품을 조회했습니다.", sellerProductService.list(storeId));
    }

    @Operation(summary = "강제 판매중지 · 해제", description = """
            blocked=true → BLOCKED(이미 BLOCKED 면 사유만 바꿈), false → BLOCKED 였으면 STOPPED · 아니면 그대로.
            에러: 400 PRODUCT_BLOCK_REASON_REQUIRED · INVALID_INPUT / 404 PRODUCT_NOT_FOUND(없거나 삭제)""")
    @PatchMapping("/products/{productId}/block")
    public ApiResponse<ProductBlockResponse> block(@PathVariable Long productId, @Valid @RequestBody ProductBlockRequest request) {
        return ApiResponse.success("상품 판매 상태를 변경했습니다.", adminProductService.block(productId, request));
    }

    @Operation(summary = "상품 통계", description = """
            storeId 있음 → store{onSaleCount, lowStockCount, soldOutCount, stoppedCount, lowStockProducts(최대 10)} / 없음 → platform{onSaleCount, lowStockCount, soldOutCount, blockedCount}.
            삭제 안 된 ON_SALE 만 옵션 재고 합계로 — 판매중 1 이상 · 재고 부족 1~19 · 품절 0""")
    @GetMapping("/stats/products")
    public ApiResponse<ProductStatsResponse> stats(@RequestParam(required = false) Long storeId) {
        return ApiResponse.success("상품 통계를 조회했습니다.", productStatsService.stats(storeId));
    }
}
