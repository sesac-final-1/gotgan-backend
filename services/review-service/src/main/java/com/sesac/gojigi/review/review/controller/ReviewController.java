package com.sesac.gojigi.review.review.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.review.review.dto.request.ReviewCreateRequest;
import com.sesac.gojigi.review.review.dto.request.ReviewUpdateRequest;
import com.sesac.gojigi.review.review.dto.response.ReviewCreateResponse;
import com.sesac.gojigi.review.review.dto.response.ReviewResponse;
import com.sesac.gojigi.review.review.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 리뷰 — 게이트웨이 /api/reviews/** (GET 공개, 그 외 로그인) → review-service (docs/api/review.md) */
@Tag(name = "리뷰", description = "구매확정한 상품주문의 리뷰")
@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @Operation(summary = "리뷰 작성", description = """
            인가: 로그인 (작성자 = X-User-Id). 구매확정한 내 상품주문당 1개, 구매확정 후 30일 이내.
            본문은 일반 텍스트 20~2,000자 — 태그는 지우고 저장한다. 사진은 아직 받지 않는다(REV-03).
            에러: 400 INVALID_INPUT(형식) · 401 UNAUTHORIZED · 403 REVIEW_NOT_ELIGIBLE(자격 없음 · 남의 상품주문) ·
            409 REVIEW_ALREADY_WRITTEN(이미 작성 — 삭제한 리뷰 포함) · 409 REVIEW_WRITE_EXPIRED(30일 지남)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "작성"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEW_NOT_ELIGIBLE"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "REVIEW_ALREADY_WRITTEN · REVIEW_WRITE_EXPIRED")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<ReviewCreateResponse>> write(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @Valid @RequestBody ReviewCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("리뷰를 등록했습니다.", reviewService.write(user.userId(), request)));
    }

    @Operation(summary = "내 리뷰 수정", description = """
            인가: 로그인 (작성자 본인). 별점 · 한줄 요약 · 본문 · 사이즈 체감을 통째로 바꾼다 — 작성과 같은 규칙, 기한 없음.
            응답은 수정된 값(200). 판매자 답글은 그대로 남는다.
            에러: 400 INVALID_INPUT(형식) · 401 UNAUTHORIZED · 404 REVIEW_NOT_FOUND(없는 · 삭제된 · 다른 회원의 리뷰) ·
            409 REVIEW_BLINDED(관리자 블라인드 — 해제는 관리자만)""")
    @PutMapping("/{reviewId}")
    public ApiResponse<ReviewResponse> edit(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @PathVariable Long reviewId, @Valid @RequestBody ReviewUpdateRequest request) {
        return ApiResponse.success("리뷰를 수정했습니다.", reviewService.edit(user.userId(), reviewId, request));
    }

    @Operation(summary = "내 리뷰 삭제", description = """
            인가: 로그인 (작성자 본인). 소프트 삭제 — 상품 리뷰 목록 · 평균 별점 · 리뷰 수에서 빠진다. 블라인드된 리뷰도 지울 수 있다.
            지운 뒤 같은 상품주문에 다시 쓸 수 없다(작성은 409 REVIEW_ALREADY_WRITTEN).
            에러: 401 UNAUTHORIZED · 404 REVIEW_NOT_FOUND(없는 · 이미 삭제된 · 다른 회원의 리뷰)""")
    @DeleteMapping("/{reviewId}")
    public ApiResponse<Void> delete(@Parameter(hidden = true) @CurrentUser AuthUser user, @PathVariable Long reviewId) {
        reviewService.delete(user.userId(), reviewId);
        return ApiResponse.success("리뷰를 삭제했습니다.");
    }
}
