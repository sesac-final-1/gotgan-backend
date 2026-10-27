package com.sesac.gojigi.product.image.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.product.image.dto.request.ImagePresignRequest;
import com.sesac.gojigi.product.image.dto.response.ImagePresignResponse;
import com.sesac.gojigi.product.image.service.ProductImagePresignService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 상품 이미지 업로드 주소 (product.md 17절) — 판매자 상품 API 와 같은 경로 아래, 이미지 코드는 image 패키지에 모은다 */
@Tag(name = "판매자 상품", description = "인가: SELLER — X-Store-Id 스토어의 상품만")
@RestController
@RequestMapping("/api/seller/products/images")
@RequiredArgsConstructor
public class SellerProductImageController {

    private final ProductImagePresignService productImagePresignService;

    @Operation(summary = "상품 이미지 업로드 주소 발급", description = """
            인가: SELLER (스토어 = X-Store-Id). 이미지마다 images 버킷 presigned PUT 주소를 발급한다(10분, 응답은 요청과 같은 순서).
            1~5개 · image/jpeg · image/png · image/webp · 각 10MB 이하. 키는 products/{storeId}/{UUID}.{jpg|png|webp}.
            브라우저가 uploadUrl 로 PUT(Content-Type · 크기를 요청과 같게 — 서명에 포함돼 다르면 S3 403) → 받은 key 를 등록 · 수정 imageKeys 로.
            에러: 400 INVALID_INPUT · PRODUCT_IMAGE_INVALID("n번째 파일: …", 하나만 틀려도 전체 거절) / 403 FORBIDDEN(스토어 헤더 없음) / 502 EXTERNAL_API_ERROR""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "발급"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT · PRODUCT_IMAGE_INVALID"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "EXTERNAL_API_ERROR")
    })
    @PostMapping("/presign")
    public ResponseEntity<ApiResponse<List<ImagePresignResponse>>> presign(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @Valid @RequestBody ImagePresignRequest request) {
        if (user.storeId() == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return ResponseEntity.ok(ApiResponse.success("업로드 주소를 발급했습니다.",
                productImagePresignService.presign(user.storeId(), request)));
    }
}
