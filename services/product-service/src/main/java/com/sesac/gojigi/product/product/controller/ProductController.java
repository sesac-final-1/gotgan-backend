package com.sesac.gojigi.product.product.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.product.product.dto.response.ProductDetailResponse;
import com.sesac.gojigi.product.product.service.ProductQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 공개 상품 조회 (product.md 9 · 10절) — 구매자 화면 · 다른 서비스(chat 상품 카드 등)가 부름. 게이트웨이 GET /api/products/** 공개 */
@Tag(name = "상품", description = "공개 — 로그인 없음")
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductQueryService productQueryService;

    @Operation(summary = "상품 상세", description = """
            공개. 판매중지 · 강제 판매중지 · 카테고리 꺼짐 · 스토어 정지/폐점 상품도 200 으로 보여 주고 purchasable=false(담기 · 구매 버튼 비활성).
            재고 수는 공개하지 않고 옵션별 · 상품 soldOut 만. 옵션 없는 상품도 값이 null 인 옵션 1개 — 장바구니는 productOptionId 로.
            images 는 URL 배열(첫 번째가 대표), 삭제된 옵션 조합은 빠짐 (product.md 10절).
            에러: 404 PRODUCT_NOT_FOUND(없거나 삭제된 상품)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 (구매 불가여도 200)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND")
    })
    @GetMapping("/{productId}")
    public ResponseEntity<ApiResponse<ProductDetailResponse>> get(
            @Parameter(description = "상품 ID", example = "101") @PathVariable Long productId) {
        return ResponseEntity.ok(ApiResponse.success("상품을 조회했습니다.", productQueryService.get(productId)));
    }
}
