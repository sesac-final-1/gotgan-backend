package com.sesac.gojigi.store.apply.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.store.apply.dto.request.SellerApplyRequest;
import com.sesac.gojigi.store.apply.dto.response.SellerApplyResponse;
import com.sesac.gojigi.store.apply.service.SellerApplyService;
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

/** 입점 신청 — 게이트웨이 /api/seller/apply/** (BUYER) → store-service (docs/api/store.md 2절) */
@Tag(name = "입점 신청", description = "구매자가 판매자로 입점 신청 — 인가: BUYER")
@RestController
@RequestMapping("/api/seller/apply")
@RequiredArgsConstructor
public class SellerApplyController {

    private final SellerApplyService sellerApplyService;

    @Operation(summary = "입점 신청", description = """
            인가: BUYER (신청자 = X-User-Id, 승인 때 StoreApproved.userId). 4단계 폼을 한 번에 제출하고 심사대기(PENDING)로 접수한다.
            서류는 presign 으로 올린 내 키(applications/{userId}/...) — 사업자등록증 · 통신판매업 신고증 · 통장 사본 각 1, 상품 사진 3~10(JPG · PNG).
            형식 · 크기는 S3 에서 확인한다. 응답 applicationNo(AP-yyMM-id)는 표시 전용, 식별은 applicationId.
            에러: 400 INVALID_INPUT(형식) · 400 APPLY_DOCUMENT_INVALID(서류) · 401 UNAUTHORIZED ·
            409 APPLY_STORE_ALREADY_EXISTS(스토어 있음, 폐점 포함) · 409 APPLY_IN_PROGRESS · 409 APPLY_REAPPLY_RESTRICTED(반려 후 30일) ·
            409 APPLY_SLUG_DUPLICATED · 409 APPLY_STORE_NAME_DUPLICATED · 502 EXTERNAL_API_ERROR(S3)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "접수"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT · APPLY_DOCUMENT_INVALID"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "APPLY_STORE_ALREADY_EXISTS · APPLY_IN_PROGRESS · APPLY_REAPPLY_RESTRICTED · APPLY_SLUG_DUPLICATED · APPLY_STORE_NAME_DUPLICATED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "EXTERNAL_API_ERROR")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<SellerApplyResponse>> apply(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @Valid @RequestBody SellerApplyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("입점 신청을 접수했습니다.", sellerApplyService.apply(user.userId(), request)));
    }
}
