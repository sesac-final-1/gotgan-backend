package com.sesac.gojigi.review.inquiry.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.review.inquiry.dto.request.InquiryCreateRequest;
import com.sesac.gojigi.review.inquiry.dto.request.InquiryUpdateRequest;
import com.sesac.gojigi.review.inquiry.dto.response.InquiryCreateResponse;
import com.sesac.gojigi.review.inquiry.dto.response.InquiryResponse;
import com.sesac.gojigi.review.inquiry.service.InquiryService;
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

/**
 * 구매자 상품 문의 — 게이트웨이 /api/inquiries/** (GET 공개, 그 외 로그인) → review-service.
 * 등록 · 수정 · 삭제(13 ~ 15절) — docs/api/review.md. 목록 · 판매자 답변은 다음 PR.
 */
@Tag(name = "상품 문의", description = "상품 상세 Q&A — 등록 · 수정 · 삭제")
@RestController
@RequestMapping("/api/inquiries")
@RequiredArgsConstructor
public class InquiryController {

    private final InquiryService inquiryService;

    @Operation(summary = "상품 문의 등록", description = """
            인가: 로그인 (작성자 = X-User-Id). 구매 여부와 관계없이 누구나 문의할 수 있다. 판매 중지 · 품절 상품도 가능(삭제된 상품만 불가).
            내용은 일반 텍스트 1~1,000자 — 태그는 지우고 저장한다. secret=true 면 작성자 · 판매자만 내용을 본다.
            스토어 · 상품명 · 대표 사진(product) · 작성자 표시 이름(user — 닉네임, 없으면 가운데 가린 실명)은 등록 시점 값을 저장한다.
            에러: 400 INVALID_INPUT(형식) · 401 UNAUTHORIZED · 404 INQUIRY_PRODUCT_NOT_FOUND(없는 · 삭제된 상품) ·
            502 EXTERNAL_API_ERROR(product · user 호출 실패)""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "등록"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "INQUIRY_PRODUCT_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "EXTERNAL_API_ERROR")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<InquiryCreateResponse>> write(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @Valid @RequestBody InquiryCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("상품 문의를 등록했습니다.", inquiryService.write(user.userId(), request)));
    }

    @Operation(summary = "내 문의 수정", description = """
            인가: 로그인 (작성자 본인). 내용 · 비밀글 여부를 통째로 바꾼다 — 등록과 같은 규칙, 상품은 바꿀 수 없다. 응답은 수정된 값(200).
            판매자가 답변한 뒤에는 수정할 수 없다(삭제는 가능).
            에러: 400 INVALID_INPUT(형식) · 401 UNAUTHORIZED · 404 INQUIRY_NOT_FOUND(없는 · 삭제된 · 다른 회원의 문의) ·
            409 INQUIRY_ALREADY_ANSWERED(답변 완료)""")
    @PutMapping("/{inquiryId}")
    public ApiResponse<InquiryResponse> edit(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @PathVariable Long inquiryId, @Valid @RequestBody InquiryUpdateRequest request) {
        return ApiResponse.success("상품 문의를 수정했습니다.", inquiryService.edit(user.userId(), inquiryId, request));
    }

    @Operation(summary = "내 문의 삭제", description = """
            인가: 로그인 (작성자 본인). 소프트 삭제 — 상품 문의 목록 · 판매자 문의 목록에서 빠진다. 답변이 달린 문의도 지울 수 있다.
            에러: 401 UNAUTHORIZED · 404 INQUIRY_NOT_FOUND(없는 · 이미 삭제된 · 다른 회원의 문의)""")
    @DeleteMapping("/{inquiryId}")
    public ApiResponse<Void> delete(@Parameter(hidden = true) @CurrentUser AuthUser user, @PathVariable Long inquiryId) {
        inquiryService.delete(user.userId(), inquiryId);
        return ApiResponse.success("상품 문의를 삭제했습니다.");
    }
}
