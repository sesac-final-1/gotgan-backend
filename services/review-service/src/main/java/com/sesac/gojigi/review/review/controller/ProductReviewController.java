package com.sesac.gojigi.review.review.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.review.review.dto.request.ProductReviewSort;
import com.sesac.gojigi.review.review.dto.response.ProductReviewResponse;
import com.sesac.gojigi.review.review.dto.response.ProductReviewSummaryResponse;
import com.sesac.gojigi.review.review.service.ProductReviewQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 상품 상세 리뷰 (공개) — 게이트웨이 /api/reviews/** GET 은 로그인 없이 통과 (docs/api/review.md 9절) */
@Tag(name = "상품 리뷰", description = "상품 상세 리뷰 탭 — 공개")
@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ProductReviewController {

    private final ProductReviewQueryService productReviewQueryService;

    @Operation(summary = "상품 리뷰 목록", description = """
            인가: 없음(공개). 블라인드 · 삭제 제외. rating 으로 별점 하나만(선택), sort: LATEST(기본) · RATING_HIGH · RATING_LOW.
            page(0부터) · size(1~50, 기본 10). 없는 상품은 빈 목록.
            에러: 400 INVALID_INPUT(productId 없음 · rating 1~5 밖 · sort 모르는 값 · page · size)""")
    @GetMapping
    public ApiResponse<PageResponse<ProductReviewResponse>> list(@RequestParam Long productId,
            @RequestParam(required = false) @Min(1) @Max(5) Integer rating,
            @RequestParam(defaultValue = "LATEST") ProductReviewSort sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return ApiResponse.success("리뷰 목록을 조회했습니다.", productReviewQueryService.list(productId, rating, sort, page, size));
    }

    @Operation(summary = "상품 리뷰 요약", description = """
            인가: 없음(공개). 평균 · 리뷰 수 · 별점 분포(5→1) · 사이즈 체감 분포(SMALL · TRUE_TO_SIZE · LARGE) — 블라인드 · 삭제 제외.
            없는 상품은 0. 에러: 400 INVALID_INPUT(productId 없음 · 숫자 아님)""")
    @GetMapping("/summary")
    public ApiResponse<ProductReviewSummaryResponse> summarize(@RequestParam Long productId) {
        return ApiResponse.success("리뷰 요약을 조회했습니다.", productReviewQueryService.summarize(productId));
    }
}
