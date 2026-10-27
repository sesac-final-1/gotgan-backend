package com.sesac.gojigi.product.product.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.product.product.dto.request.ProductCreateRequest;
import com.sesac.gojigi.product.product.dto.response.SellerProductDetailResponse;
import com.sesac.gojigi.product.product.service.SellerProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    /** 판매자 API 공통 — 스토어 헤더가 없으면 403 (product.md 12절 아래 공통 규칙) */
    private static Long storeId(AuthUser user) {
        if (user.storeId() == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return user.storeId();
    }
}
