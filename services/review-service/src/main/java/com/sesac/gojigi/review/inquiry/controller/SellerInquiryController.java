package com.sesac.gojigi.review.inquiry.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.review.inquiry.dto.request.InquiryAnswerRequest;
import com.sesac.gojigi.review.inquiry.dto.request.SellerInquiryTab;
import com.sesac.gojigi.review.inquiry.dto.response.InquiryAnswerResponse;
import com.sesac.gojigi.review.inquiry.dto.response.SellerInquiryResponse;
import com.sesac.gojigi.review.inquiry.dto.response.SellerInquirySummaryResponse;
import com.sesac.gojigi.review.inquiry.service.InquiryService;
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

/** 판매자 상품 문의 (/seller/qna) — 게이트웨이 /api/seller/** (SELLER) → review-service, 스토어 = X-Store-Id (docs/api/review.md 18 ~ 20절) */
@Tag(name = "판매자 상품 문의", description = "내 스토어 상품 문의 목록 · 탭 숫자 · 답변 — 인가: SELLER")
@RestController
@RequestMapping("/api/seller/inquiries")
@RequiredArgsConstructor
public class SellerInquiryController {

    private final InquiryService inquiryService;

    @Operation(summary = "판매자 문의 목록", description = """
            인가: SELLER (X-Store-Id 의 스토어). 내 스토어 상품 문의(삭제 제외) 최신 등록순 — 비밀글도 내용을 보여 준다(secret 은 자물쇠 표시용).
            tab: ALL(기본) · UNANSWERED(미답변) · ANSWERED(답변완료). page(0부터) · size(1~50, 기본 10).
            에러: 400 INVALID_INPUT(tab · page · size) · 401 UNAUTHORIZED · 403 FORBIDDEN(스토어 정보 없음)""")
    @GetMapping
    public ApiResponse<PageResponse<SellerInquiryResponse>> list(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @RequestParam(defaultValue = "ALL") SellerInquiryTab tab,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return ApiResponse.success("문의 목록을 조회했습니다.", inquiryService.listForSeller(user.storeId(), tab, page, size));
    }

    @Operation(summary = "판매자 문의 탭 숫자", description = """
            인가: SELLER. 미답변 · 답변완료 · 전체(삭제 제외) — 문의 화면 탭, 판매자 메뉴 배지 · 대시보드 "미답변 문의".
            에러: 401 UNAUTHORIZED · 403 FORBIDDEN(스토어 정보 없음)""")
    @GetMapping("/summary")
    public ApiResponse<SellerInquirySummaryResponse> summarize(@Parameter(hidden = true) @CurrentUser AuthUser user) {
        return ApiResponse.success("문의 숫자를 조회했습니다.", inquiryService.summarizeForSeller(user.storeId()));
    }

    @Operation(summary = "답변 작성 · 수정", description = """
            인가: SELLER. 문의당 답변 1개 — 없으면 작성, 있으면 덮어쓴다(삭제 없음). 일반 텍스트 1,000자 이하, 태그는 지우고 저장.
            답변이 달리면 구매자는 그 문의를 수정할 수 없다(삭제는 가능).
            에러: 400 INVALID_INPUT(형식 · 태그만 있음) · 401 UNAUTHORIZED · 403 FORBIDDEN(스토어 정보 없음) ·
            404 INQUIRY_NOT_FOUND(없음 · 삭제됨 · 다른 스토어 문의)""")
    @PutMapping("/{inquiryId}/answer")
    public ApiResponse<InquiryAnswerResponse> answer(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @PathVariable Long inquiryId, @Valid @RequestBody InquiryAnswerRequest request) {
        return ApiResponse.success("답변을 저장했습니다.", inquiryService.answer(user.storeId(), inquiryId, request));
    }
}
