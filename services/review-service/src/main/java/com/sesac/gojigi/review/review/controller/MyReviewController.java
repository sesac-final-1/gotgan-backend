package com.sesac.gojigi.review.review.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.review.review.dto.response.MyReviewResponse;
import com.sesac.gojigi.review.review.dto.response.WritableReviewResponse;
import com.sesac.gojigi.review.review.service.MyReviewQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 마이페이지 리뷰 관리 (/mypage/reviews) — 게이트웨이 /api/reviews/** 는 GET 공개라 X-User-Id 가 없으면 여기서 401 (docs/api/review.md 4 · 5절) */
@Tag(name = "내 리뷰", description = "마이페이지 리뷰 관리 — 내가 쓴 리뷰 · 작성 가능한 리뷰")
@RestController
@RequestMapping("/api/reviews/me")
@RequiredArgsConstructor
public class MyReviewController {

    private final MyReviewQueryService myReviewQueryService;

    @Operation(summary = "내가 쓴 리뷰", description = """
            인가: 로그인. 삭제한 리뷰 제외, 최신 작성순. 블라인드된 리뷰도 blinded=true 로 보인다(작성자 본인에게만).
            상품명 · 옵션명 · 대표 사진은 구매확정 시점 스냅샷. page(0부터) · size(1~50, 기본 10).
            에러: 400 INVALID_INPUT(page · size) · 401 UNAUTHORIZED""")
    @GetMapping
    public ApiResponse<PageResponse<MyReviewResponse>> listMine(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return ApiResponse.success("내가 쓴 리뷰를 조회했습니다.", myReviewQueryService.listMine(user.userId(), page, size));
    }

    @Operation(summary = "작성 가능한 리뷰", description = """
            인가: 로그인. 구매확정 후 30일 안 · 아직 리뷰가 없는(삭제한 리뷰가 있으면 제외) 상품주문, 기한 임박순. 페이징 없음.
            에러: 401 UNAUTHORIZED""")
    @GetMapping("/writable")
    public ApiResponse<List<WritableReviewResponse>> listWritable(@Parameter(hidden = true) @CurrentUser AuthUser user) {
        return ApiResponse.success("작성 가능한 리뷰를 조회했습니다.", myReviewQueryService.listWritable(user.userId()));
    }
}
