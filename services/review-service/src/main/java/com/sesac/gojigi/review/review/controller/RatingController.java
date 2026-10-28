package com.sesac.gojigi.review.review.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.review.review.dto.response.ProductRatingResponse;
import com.sesac.gojigi.review.review.dto.response.StoreRatingResponse;
import com.sesac.gojigi.review.review.service.RatingQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 여러 상품 · 스토어 평점 (공개) — 게이트웨이 /api/reviews/** GET 은 로그인 없이 통과 (docs/api/review.md 10절) */
@Tag(name = "평점", description = "상품 카드 · 목록 · 스토어 화면의 평균 별점 · 리뷰 수 — 공개")
@RestController
@RequestMapping("/api/reviews/ratings")
@RequiredArgsConstructor
public class RatingController {

    /** 한 번에 묻는 ID 수 — product 내부 API(product-options) 와 같은 100 */
    private static final int MAX_IDS = 100;

    private final RatingQueryService ratingQueryService;

    @Operation(summary = "여러 상품 평점", description = """
            인가: 없음(공개). ids=101,102 또는 ids=101&ids=102, 1~100개. 요청한 순서대로(중복은 한 번), 리뷰 없는 상품은 0 · 0.
            블라인드 · 삭제 제외. 에러: 400 INVALID_INPUT(ids 없음 · 숫자 아님 · 100개 초과)""")
    @GetMapping("/products")
    public ApiResponse<List<ProductRatingResponse>> rateProducts(
            @RequestParam @Size(min = 1, max = MAX_IDS) List<Long> ids) {
        return ApiResponse.success("상품 평점을 조회했습니다.", ratingQueryService.rateProducts(ids));
    }

    @Operation(summary = "여러 스토어 평점", description = """
            인가: 없음(공개). ids 1~100개, 요청한 순서대로, 리뷰 없는 스토어는 0 · 0. 블라인드 · 삭제 제외.
            에러: 400 INVALID_INPUT(ids 없음 · 숫자 아님 · 100개 초과)""")
    @GetMapping("/stores")
    public ApiResponse<List<StoreRatingResponse>> rateStores(@RequestParam @Size(min = 1, max = MAX_IDS) List<Long> ids) {
        return ApiResponse.success("스토어 평점을 조회했습니다.", ratingQueryService.rateStores(ids));
    }
}
