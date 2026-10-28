package com.sesac.gojigi.review.review.controller;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.review.review.dto.request.ReviewBlindRequest;
import com.sesac.gojigi.review.review.dto.response.InternalReviewResponse;
import com.sesac.gojigi.review.review.dto.response.InternalUserReviewResponse;
import com.sesac.gojigi.review.review.service.InternalReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 리뷰 내부 API — 호출자 admin-service(신고 처리 · 회원 상세), 게이트웨이 라우트 없음 · 클러스터 내부 전용 (docs/api/review.md 8절) */
@Tag(name = "리뷰 (내부)", description = "admin-service — 리뷰 조회 · 블라인드 · 회원별 리뷰")
@RestController
@RequestMapping("/internal/reviews")
@RequiredArgsConstructor
public class InternalReviewController {

    private final InternalReviewService internalReviewService;

    @Operation(summary = "회원별 리뷰 (관리자 회원 상세)", description = """
            그 회원이 쓴 리뷰 — 삭제 제외 · 블라인드 포함, 최신 작성순. page(0부터) · size(1~50, 기본 10).
            없는 회원은 빈 목록(review 는 회원을 모름). DB 조회만 하므로 재시도 가능.
            에러: 400 INVALID_INPUT(userId 없음 · 숫자 아님 · page · size 범위 밖)""")
    @GetMapping
    public ApiResponse<PageResponse<InternalUserReviewResponse>> listByUser(@RequestParam Long userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return ApiResponse.success("회원 리뷰를 조회했습니다.", internalReviewService.listByUser(userId, page, size));
    }

    @Operation(summary = "리뷰 조회 (신고 처리 화면)", description = """
            블라인드된 리뷰도 그대로(blinded · blindReason). DB 조회만 하므로 재시도 가능.
            에러: 400 INVALID_INPUT(reviewId 숫자 아님) · 404 REVIEW_NOT_FOUND(없음 · 삭제됨)""")
    @GetMapping("/{reviewId}")
    public ApiResponse<InternalReviewResponse> get(@PathVariable Long reviewId) {
        return ApiResponse.success("리뷰를 조회했습니다.", internalReviewService.get(reviewId));
    }

    @Operation(summary = "블라인드 · 해제", description = """
            blinded=true 면 reason 필수(255자 이하), 해제는 reason 생략 가능(저장하지 않음). 응답은 조회와 같은 모양.
            같은 상태 재요청은 200 그대로(멱등 — 처음 사유 · 시각 유지). 블라인드되면 구매자 목록 · 평균 · 리뷰 수에서 빠지고
            작성자 수정 · 판매자 답글이 막힌다.
            에러: 400 INVALID_INPUT(형식 · 블라인드인데 사유 없음) · 404 REVIEW_NOT_FOUND(없음 · 삭제됨)""")
    @PatchMapping("/{reviewId}/blind")
    public ApiResponse<InternalReviewResponse> changeBlind(@PathVariable Long reviewId,
            @Valid @RequestBody ReviewBlindRequest request) {
        return ApiResponse.success(request.blinded() ? "리뷰를 블라인드했습니다." : "리뷰 블라인드를 해제했습니다.",
                internalReviewService.changeBlind(reviewId, request));
    }
}
