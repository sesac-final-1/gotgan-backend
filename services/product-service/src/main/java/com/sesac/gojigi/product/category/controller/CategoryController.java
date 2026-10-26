package com.sesac.gojigi.product.category.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.product.category.dto.response.CategoryTreeResponse;
import com.sesac.gojigi.product.category.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 카테고리 트리 (CAT-01 · product.md 8절) — 쇼핑몰 탐색 · 상품 등록 · 입점 신청이 같은 API 를 쓴다 */
@Tag(name = "카테고리", description = "공개 — 로그인 없음")
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "카테고리 트리", description = "사용 중인 것만 — 꺼진 상위는 하위까지 빠짐. 상위 · 하위 sortOrder 순 (값은 응답에 없음). 수수료율 없음")
    @GetMapping
    public ApiResponse<List<CategoryTreeResponse>> tree() {
        return ApiResponse.success("카테고리를 조회했습니다.", categoryService.publicTree());
    }
}
