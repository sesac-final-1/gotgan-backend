package com.sesac.gojigi.review.review.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.review.review.dto.request.ReviewReplyRequest;
import com.sesac.gojigi.review.review.dto.request.SellerReviewTab;
import com.sesac.gojigi.review.review.dto.response.ReviewReplyResponse;
import com.sesac.gojigi.review.review.dto.response.SellerReviewResponse;
import com.sesac.gojigi.review.review.dto.response.SellerReviewSummaryResponse;
import com.sesac.gojigi.review.review.service.SellerReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 판매자 리뷰 관리 (/seller/reviews) — 게이트웨이 /api/seller/** (SELLER) → review-service, 스토어 = X-Store-Id (docs/api/review.md 6 · 7절) */
@Tag(name = "판매자 리뷰", description = "내 스토어 상품 리뷰 목록 · 평점 요약 · 답글 — 인가: SELLER")
@RestController
@RequestMapping("/api/seller/reviews")
@RequiredArgsConstructor
public class SellerReviewController {

    private final SellerReviewService sellerReviewService;

    @Operation(summary = "내 스토어 리뷰 목록", description = """
            인가: SELLER (X-Store-Id 의 스토어). 삭제 제외 · 블라인드 포함(blinded=true — '블라인드됨', 답글 불가), 최신 작성순.
            tab: ALL(기본) · NO_REPLY(답글 없고 블라인드 아님) · LOW_RATING(1~2점). productId 로 상품 하나만. page(0부터) · size(1~50, 기본 10).
            에러: 400 INVALID_INPUT(tab · page · size) · 401 UNAUTHORIZED · 403 FORBIDDEN(스토어 정보 없음)""")
    @GetMapping
    public ApiResponse<PageResponse<SellerReviewResponse>> list(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @RequestParam(defaultValue = "ALL") SellerReviewTab tab,
            @RequestParam(required = false) Long productId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return ApiResponse.success("리뷰 목록을 조회했습니다.", sellerReviewService.list(user.storeId(), tab, productId, page, size));
    }

    @Operation(summary = "내 스토어 평점 요약", description = """
            인가: SELLER. 스토어 평균 · 별점 분포(5→1) · 상품별 평균(높은순) — 블라인드 · 삭제 제외(구매자에게 보이는 리뷰 기준).
            tabCounts 는 목록 탭과 같은 조건(전체는 블라인드 포함).
            에러: 401 UNAUTHORIZED · 403 FORBIDDEN(스토어 정보 없음)""")
    @GetMapping("/summary")
    public ApiResponse<SellerReviewSummaryResponse> summarize(@Parameter(hidden = true) @CurrentUser AuthUser user) {
        return ApiResponse.success("평점 요약을 조회했습니다.", sellerReviewService.summarize(user.storeId()));
    }

    @Operation(summary = "답글 작성 · 수정", description = """
            인가: SELLER. 리뷰당 답글 1개 — 없으면 작성, 있으면 덮어쓴다(삭제 없음). 일반 텍스트 1,000자 이하, 태그는 지우고 저장.
            에러: 400 INVALID_INPUT(형식 · 태그만 있음) · 401 UNAUTHORIZED · 403 FORBIDDEN(스토어 정보 없음) ·
            404 REVIEW_NOT_FOUND(없음 · 삭제됨 · 다른 스토어 리뷰) · 409 REVIEW_BLINDED(관리자 블라인드)""")
    @PutMapping("/{reviewId}/reply")
    public ApiResponse<ReviewReplyResponse> reply(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @PathVariable Long reviewId, @Valid @RequestBody ReviewReplyRequest request) {
        return ApiResponse.success("답글을 저장했습니다.", sellerReviewService.reply(user.storeId(), reviewId, request));
    }
}
