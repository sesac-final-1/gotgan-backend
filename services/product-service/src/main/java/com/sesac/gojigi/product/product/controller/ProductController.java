package com.sesac.gojigi.product.product.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.product.product.dto.request.ProductSearchRequest;
import com.sesac.gojigi.product.product.dto.response.ProductDetailResponse;
import com.sesac.gojigi.product.product.dto.response.ProductSummaryResponse;
import com.sesac.gojigi.product.product.entity.ProductSort;
import com.sesac.gojigi.product.product.service.ProductQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 공개 상품 조회 (product.md 9 · 10절) — 구매자 화면 · 다른 서비스(chat 상품 카드 등)가 부름. 게이트웨이 GET /api/products/** 공개 */
@Tag(name = "상품", description = "공개 — 로그인 없음")
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductQueryService productQueryService;

    @Operation(summary = "상품 목록 · 검색", description = """
            공개. 목록 · 검색 · 메인 화면 · 스토어 홈 공용 — 쇼핑몰에 보이는 상품만(판매중 · 카테고리 사용 중 · 스토어 운영 중 · 삭제 안 됨).
            categoryId: 상위면 그 아래 하위 전부, 하위면 그 하위만 / q: 상품명 · 하위 카테고리 이름 포함(앞뒤 공백 제거, 비면 조건 없음) /
            minPrice ≤ salePrice < maxPrice / excludeSoldOut / sort NEW · PRICE_ASC · PRICE_DESC(같으면 productId 큰 순) / page 0부터 · size 기본 20 최대 100.
            메인 화면(PRD-14)은 카테고리마다 ?categoryId={상위}&sort=NEW&size=8. 조건에 맞는 상품이 없거나 없는 · 꺼진 카테고리면 빈 페이지 (product.md 9절).
            에러: 400 INVALID_INPUT(minPrice > maxPrice · 음수 · 모르는 sort · size 100 초과 · q 50자 초과)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 (없으면 빈 페이지)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ProductSummaryResponse>>> search(
            @Parameter(description = "카테고리 — 상위 ID 면 그 아래 하위 전부, 하위 ID 면 그 하위만", example = "1")
            @RequestParam(required = false) Long categoryId,
            @Parameter(description = "스토어 홈", example = "1")
            @RequestParam(required = false) Long storeId,
            @Parameter(description = "상품명 · 하위 카테고리 이름 포함 검색 (앞뒤 공백 제거 후 50자 이하, 비면 조건 없음)", example = "감귤")
            @RequestParam(required = false) String q,
            @Parameter(description = "판매가 하한 (포함)", example = "10000")
            @RequestParam(required = false) @PositiveOrZero(message = "가격은 0 이상이어야 합니다.") Long minPrice,
            @Parameter(description = "판매가 상한 (미포함)", example = "30000")
            @RequestParam(required = false) @PositiveOrZero(message = "가격은 0 이상이어야 합니다.") Long maxPrice,
            @Parameter(description = "true 면 품절 상품 제외", example = "false")
            @RequestParam(required = false) Boolean excludeSoldOut,
            @Parameter(description = "NEW(기본) · PRICE_ASC · PRICE_DESC", example = "NEW")
            @RequestParam(required = false) ProductSort sort,
            @Parameter(description = "페이지 (0부터, 기본 0)", example = "0")
            @RequestParam(required = false) @PositiveOrZero(message = "페이지는 0 이상이어야 합니다.") Integer page,
            @Parameter(description = "페이지 크기 (기본 20, 최대 100)", example = "20")
            @RequestParam(required = false) @Min(value = 1, message = "페이지 크기는 1 이상이어야 합니다.")
            @Max(value = 100, message = "페이지 크기는 100 이하여야 합니다.") Integer size) {
        ProductSearchRequest request = new ProductSearchRequest(categoryId, storeId, q, minPrice, maxPrice, excludeSoldOut, sort, page, size);
        return ResponseEntity.ok(ApiResponse.success("상품을 조회했습니다.", productQueryService.search(request)));
    }

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
