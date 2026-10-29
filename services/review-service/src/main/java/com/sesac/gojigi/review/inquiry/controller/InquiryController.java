package com.sesac.gojigi.review.inquiry.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.auth.CurrentUserArgumentResolver;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.review.inquiry.dto.request.InquiryCreateRequest;
import com.sesac.gojigi.review.inquiry.dto.request.InquiryUpdateRequest;
import com.sesac.gojigi.review.inquiry.dto.response.InquiryCreateResponse;
import com.sesac.gojigi.review.inquiry.dto.response.InquiryResponse;
import com.sesac.gojigi.review.inquiry.dto.response.ProductInquiryResponse;
import com.sesac.gojigi.review.inquiry.service.InquiryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 구매자 · 공개 상품 문의 — 게이트웨이 /api/inquiries/** (GET 공개, 그 외 로그인) → review-service.
 * 등록 · 수정 · 삭제(13 ~ 15절), 상품 Q&A · 내 문의(16 · 17절) — docs/api/review.md. 판매자는 SellerInquiryController.
 */
@Tag(name = "상품 문의", description = "상품 상세 Q&A — 등록 · 수정 · 삭제 · 상품 문의 · 내 문의")
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

    @Operation(summary = "상품 문의 목록 (공개)", description = """
            인가: 없음(공개). 로그인 상태면 게이트웨이가 넣은 X-User-Id · X-Store-Id 로 비밀글을 연다.
            삭제 제외, 최신 등록순. 비밀글은 작성자 · 그 스토어 판매자가 아니면 content · answerContent 가 null(답변 여부 · 작성자 이름은 보임).
            mine = 내가 쓴 문의(수정 · 삭제 버튼). 탭 숫자는 totalElements. page(0부터) · size(1~50, 기본 10). 없는 상품은 빈 목록.
            에러: 400 INVALID_INPUT(productId 없음 · page · size)""")
    @GetMapping
    public ApiResponse<PageResponse<ProductInquiryResponse>> listForProduct(@RequestParam Long productId,
            @Parameter(hidden = true) @RequestHeader(value = CurrentUserArgumentResolver.USER_ID_HEADER, required = false) Long viewerUserId,
            @Parameter(hidden = true) @RequestHeader(value = CurrentUserArgumentResolver.STORE_ID_HEADER, required = false) Long viewerStoreId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return ApiResponse.success("상품 문의를 조회했습니다.",
                inquiryService.listForProduct(productId, viewerUserId, viewerStoreId, page, size));
    }

    @Operation(summary = "내 문의", description = """
            인가: 로그인. 내가 쓴 문의(삭제 제외 · 비밀글 포함) 최신 등록순 — 상품명 · 대표 사진은 등록 시점 값, 답변 포함.
            page(0부터) · size(1~50, 기본 10). 에러: 400 INVALID_INPUT(page · size) · 401 UNAUTHORIZED""")
    @GetMapping("/me")
    public ApiResponse<PageResponse<InquiryResponse>> listMine(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return ApiResponse.success("내 문의를 조회했습니다.", inquiryService.listMine(user.userId(), page, size));
    }
}
