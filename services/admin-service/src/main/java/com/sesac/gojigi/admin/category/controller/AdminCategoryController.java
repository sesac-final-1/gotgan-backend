package com.sesac.gojigi.admin.category.controller;

import com.sesac.gojigi.admin.category.dto.request.AdminCategoryCreateRequest;
import com.sesac.gojigi.admin.category.dto.request.AdminCategoryUpdateRequest;
import com.sesac.gojigi.admin.category.service.AdminCategoryService;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 관리자 카테고리 (CAT-02) — product 카테고리 + settlement 수수료 조합. 수수료 조회 · 변경 화면은 /api/admin/commissions */
@Tag(name = "관리자 카테고리", description = "product · settlement 조합 — 인가: ADMIN")
@RestController
@RequestMapping("/api/admin/categories")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final AdminCategoryService adminCategoryService;

    @Operation(summary = "카테고리 트리 (사용 중지 포함)", description = "product GET /internal/categories 그대로")
    @GetMapping
    public ApiResponse<JsonNode> tree() {
        return ApiResponse.success("카테고리를 조회했습니다.", adminCategoryService.tree());
    }

    @Operation(summary = "카테고리 생성", description = """
            상위: commissionRate 필수 → product 생성(사용 중지) → settlement 수수료(오늘부터) → 사용으로. 하위: product 생성(사용 중).
            에러: 400 CATEGORY_COMMISSION_RATE_REQUIRED · INVALID_INPUT / product 오류 그대로(404 CATEGORY_NOT_FOUND · 409 CATEGORY_NAME_DUPLICATED ·
            400 CATEGORY_DEPTH_EXCEEDED) / 502 CATEGORY_ACTIVATION_PENDING(만들었지만 수수료 · 사용 처리 실패 — 꺼진 채 남음)""")
    @PostMapping
    public ResponseEntity<ApiResponse<JsonNode>> create(@Valid @RequestBody AdminCategoryCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("카테고리를 만들었습니다.", adminCategoryService.create(request)));
    }

    @Operation(summary = "카테고리 수정 · 사용 여부", description = """
            이름 · 영문명 · 정렬 → product PUT, active 가 바뀌면 product PATCH. 상위를 켤 때 settlement 현재 수수료율이 없으면 409 CATEGORY_COMMISSION_REQUIRED.
            product 오류 그대로(404 · 409)""")
    @PutMapping("/{categoryId}")
    public ApiResponse<JsonNode> update(@PathVariable Long categoryId, @Valid @RequestBody AdminCategoryUpdateRequest request) {
        return ApiResponse.success("카테고리를 수정했습니다.", adminCategoryService.update(categoryId, request));
    }
}
