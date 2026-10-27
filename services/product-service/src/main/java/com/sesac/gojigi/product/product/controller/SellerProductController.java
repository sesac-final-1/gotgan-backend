package com.sesac.gojigi.product.product.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.product.product.dto.request.ProductCreateRequest;
import com.sesac.gojigi.product.product.dto.request.ProductStatusChangeRequest;
import com.sesac.gojigi.product.product.dto.response.ProductStatusChangeResponse;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    @Operation(summary = "상품 수정", description = """
            인가: SELLER (스토어 = X-Store-Id). 등록과 같은 본문을 통째로 — status 는 무시하고 재고 · 판매 상태는 바꾸지 않음(재고 14절 · 상태 15절).
            옵션은 productOptionId 로 맞춤: 있으면 추가금만(값이 다르면 400) · 없으면 새 조합(stock 으로 시작) · 요청에 없는 기존 조합은 소프트 삭제.
            이미지는 요청 순서대로 다시 저장 — 이 상품에 이미 붙은 키 또는 presign 으로 받은 내 키. BLOCKED 상품도 수정 가능(상태 그대로).
            에러: 400 INVALID_INPUT · PRODUCT_CATEGORY_INVALID · PRODUCT_OPTION_INVALID(남의 · 삭제된 옵션 ID 포함) · PRODUCT_IMAGE_INVALID /
            403 FORBIDDEN(스토어 헤더 없음) / 404 PRODUCT_NOT_FOUND(없거나 삭제됨 · 다른 스토어 상품)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 — 판매자 상품 상세(12절)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "INVALID_INPUT · PRODUCT_CATEGORY_INVALID · PRODUCT_OPTION_INVALID · PRODUCT_IMAGE_INVALID"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND")
    })
    @PutMapping("/{productId}")
    public ResponseEntity<ApiResponse<SellerProductDetailResponse>> update(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @Parameter(description = "상품 ID", example = "3") @PathVariable Long productId,
            @Valid @RequestBody ProductCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.success("상품을 수정했습니다.", sellerProductService.update(storeId(user), productId, request)));
    }

    @Operation(summary = "판매중지 · 재개 (일괄)", description = """
            인가: SELLER (스토어 = X-Store-Id). 한 개든 여러 개든 같은 API — 건마다 따로 처리해 success · failed 로 나눠 줌(하나가 실패해도 나머지는 반영).
            ON_SALE ↔ STOPPED, 이미 그 상태면 success(멱등), 재고 0 이어도 재개 가능. 같은 ID 는 한 번만 처리(요청 순서 유지).
            failed: PRODUCT_BLOCKED(관리자 강제 판매중지 상품) · PRODUCT_NOT_FOUND(없음 · 삭제됨 · 다른 스토어 상품) (product.md 15절).
            에러(전체 거절 · 아무것도 처리 안 함): 400 INVALID_INPUT(status 가 ON_SALE · STOPPED 아님 · productIds 비었음 · 100개 초과) / 403 FORBIDDEN""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "처리 — success · failed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN")
    })
    @PatchMapping("/status")
    public ResponseEntity<ApiResponse<ProductStatusChangeResponse>> changeStatus(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @Valid @RequestBody ProductStatusChangeRequest request) {
        return ResponseEntity.ok(ApiResponse.success("판매 상태를 변경했습니다.", sellerProductService.changeStatus(storeId(user), request)));
    }

    @Operation(summary = "상품 삭제", description = """
            인가: SELLER (스토어 = X-Store-Id). 상태와 상관없이(BLOCKED 포함) 소프트 삭제 — 옵션 · 재고 이력은 남고 목록 · 상세 · 공개 화면에서 빠짐.
            지난 주문은 스냅샷이라 영향 없음, 장바구니에 담긴 옵션은 구매 불가가 됨. 화면의 일괄 삭제는 선택한 개수만큼 이 API 를 부름 (product.md 16절).
            에러: 403 FORBIDDEN(스토어 헤더 없음) / 404 PRODUCT_NOT_FOUND(없음 · 이미 삭제 · 다른 스토어 상품)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND")
    })
    @DeleteMapping("/{productId}")
    public ResponseEntity<ApiResponse<Void>> delete(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @Parameter(description = "상품 ID", example = "3") @PathVariable Long productId) {
        sellerProductService.delete(storeId(user), productId);
        return ResponseEntity.ok(ApiResponse.success("상품을 삭제했습니다."));
    }

    /** 판매자 API 공통 — 스토어 헤더가 없으면 403 (product.md 12절 아래 공통 규칙) */
    private static Long storeId(AuthUser user) {
        if (user.storeId() == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return user.storeId();
    }
}
