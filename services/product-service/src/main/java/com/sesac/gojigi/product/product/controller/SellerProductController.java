package com.sesac.gojigi.product.product.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.product.product.dto.request.ProductCreateRequest;
import com.sesac.gojigi.product.product.dto.response.SellerProductDetailResponse;
import com.sesac.gojigi.product.product.dto.response.SellerProductResponse;
import com.sesac.gojigi.product.product.service.SellerProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 판매자 상품 관리 (product.md 11~17절) — 역할 확인은 게이트웨이(/api/seller/** = SELLER), product 는 X-Store-Id 소유권만 본다 */
@Tag(name = "판매자 상품", description = "인가: SELLER — X-Store-Id 스토어의 상품만")
@RestController
@RequestMapping("/api/seller/products")
@RequiredArgsConstructor
public class SellerProductController {

    private final SellerProductService sellerProductService;

    @Operation(summary = "상품 등록", description = """
            인가: SELLER (스토어 = X-Store-Id). 옵션 · 이미지를 통째로 보낸다 (product.md 13절).
            옵션 그룹 0~2개 · 조합 1~100개(그룹이 없으면 값이 null 인 1개), 이미지는 presign 으로 받은 내 키 1~5개(첫 번째가 대표).
            판매가는 서버 계산(10원 단위 반올림), 상세 설명은 sanitizer 로 정화. 응답은 판매자 상품 상세(12절)와 같은 모양.
            에러: 400 INVALID_INPUT · PRODUCT_CATEGORY_INVALID · PRODUCT_OPTION_INVALID · PRODUCT_IMAGE_INVALID / 403 FORBIDDEN(스토어 헤더 없음)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "등록"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "INVALID_INPUT · PRODUCT_CATEGORY_INVALID · PRODUCT_OPTION_INVALID · PRODUCT_IMAGE_INVALID"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<SellerProductDetailResponse>> create(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @Valid @RequestBody ProductCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("상품을 등록했습니다.", sellerProductService.create(storeId(user), request)));
    }

    @Operation(summary = "판매자 상품 목록", description = """
            인가: SELLER (스토어 = X-Store-Id). 내 스토어 상품 전체 — 페이징 · 필터 없음(상태별 개수 · 검색 · 정렬 · 페이징은 화면), 삭제된 상품만 빠짐, 등록일 최신 순.
            hiddenReason: 쇼핑몰에 안 보이는 이유 STOPPED · BLOCKED · CATEGORY_INACTIVE · STORE_INACTIVE, 보이면 null (product.md 11절).
            에러: 403 FORBIDDEN(스토어 헤더 없음)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 (상품 없으면 빈 배열)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<List<SellerProductResponse>>> list(@Parameter(hidden = true) @CurrentUser AuthUser user) {
        return ResponseEntity.ok(ApiResponse.success("상품 목록을 조회했습니다.", sellerProductService.list(storeId(user))));
    }

    @Operation(summary = "판매자 상품 상세 (수정 화면)", description = """
            인가: SELLER (스토어 = X-Store-Id). 공개 상세(10절)에 판매자만 보는 값 — status · statusReason · hiddenReason · 옵션별 stock · 이미지 {key, url}.
            삭제된 조합은 빠짐. 수정(PUT) 때 images[].key 를 imageKeys 로 그대로 돌려보낸다 (product.md 12절).
            에러: 403 FORBIDDEN(스토어 헤더 없음) / 404 PRODUCT_NOT_FOUND(없거나 삭제됨 · 다른 스토어 상품 — 구분하지 않음)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND")
    })
    @GetMapping("/{productId}")
    public ResponseEntity<ApiResponse<SellerProductDetailResponse>> get(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @Parameter(description = "상품 ID", example = "3") @PathVariable Long productId) {
        return ResponseEntity.ok(ApiResponse.success("상품을 조회했습니다.", sellerProductService.get(storeId(user), productId)));
    }

    /** 판매자 API 공통 — 스토어 헤더가 없으면 403 (product.md 12절 아래 공통 규칙) */
    private static Long storeId(AuthUser user) {
        if (user.storeId() == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return user.storeId();
    }
}
