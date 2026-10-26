package com.sesac.gojigi.product.category.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.product.category.dto.request.CategoryActiveRequest;
import com.sesac.gojigi.product.category.dto.request.CategoryCreateRequest;
import com.sesac.gojigi.product.category.dto.request.CategoryUpdateRequest;
import com.sesac.gojigi.product.category.dto.response.AdminCategoryTreeResponse;
import com.sesac.gojigi.product.category.dto.response.CategoryResponse;
import com.sesac.gojigi.product.category.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 카테고리 관리 (CAT-02 · product.md 7절) — admin-service 의 카테고리 화면이 호출 */
@Tag(name = "카테고리 관리 (내부)", description = "admin-service 가 호출 — 인가: 내부")
@RestController
@RequestMapping("/internal/categories")
@RequiredArgsConstructor
public class InternalCategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "카테고리 트리 (비활성 포함)", description = "상위 · 하위 모두 sortOrder 순")
    @GetMapping
    public ApiResponse<List<AdminCategoryTreeResponse>> tree() {
        return ApiResponse.success("카테고리를 조회했습니다.", categoryService.adminTree());
    }

    @Operation(summary = "카테고리 생성", description = """
            parentId 없으면 상위 — 사용 중지로 생성(수수료 등록 뒤 PATCH 로 켬), 있으면 하위 — 사용 중으로 생성. sortOrder 생략 = 같은 부모 안 맨 뒤.
            에러: 400 CATEGORY_DEPTH_EXCEEDED(하위 아래) · INVALID_INPUT / 404 CATEGORY_NOT_FOUND(parentId) / 409 CATEGORY_NAME_DUPLICATED""")
    @PostMapping
    public ResponseEntity<ApiResponse<CategoryResponse>> create(@Valid @RequestBody CategoryCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("카테고리를 만들었습니다.", categoryService.create(request)));
    }

    @Operation(summary = "카테고리 수정", description = """
            이름 · 영문명 · 정렬만 (상위 · 하위 이동 없음).
            에러: 400 INVALID_INPUT / 404 CATEGORY_NOT_FOUND / 409 CATEGORY_NAME_DUPLICATED""")
    @PutMapping("/{categoryId}")
    public ApiResponse<CategoryResponse> update(@PathVariable Long categoryId, @Valid @RequestBody CategoryUpdateRequest request) {
        return ApiResponse.success("카테고리를 수정했습니다.", categoryService.update(categoryId, request));
    }

    @Operation(summary = "사용 / 사용 중지", description = """
            같은 값이면 그대로 200 (멱등). 상품 status 는 바꾸지 않고 노출 여부만 계산으로 바뀜.
            상위를 켜기 전 수수료율 확인은 admin 이 한다. 에러: 400 INVALID_INPUT / 404 CATEGORY_NOT_FOUND""")
    @PatchMapping("/{categoryId}")
    public ApiResponse<CategoryResponse> changeActive(@PathVariable Long categoryId, @Valid @RequestBody CategoryActiveRequest request) {
        return ApiResponse.success("카테고리 사용 여부를 변경했습니다.", categoryService.changeActive(categoryId, request.active()));
    }
}
