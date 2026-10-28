package com.sesac.gojigi.review.review.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.review.review.dto.request.ReviewCreateRequest;
import com.sesac.gojigi.review.review.dto.response.ReviewCreateResponse;
import com.sesac.gojigi.review.review.service.ReviewService;
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
}
