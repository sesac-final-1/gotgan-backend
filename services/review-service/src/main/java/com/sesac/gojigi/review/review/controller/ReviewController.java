package com.sesac.gojigi.review.review.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.review.review.dto.request.ProductReviewSort;
import com.sesac.gojigi.review.review.dto.request.ReviewCreateRequest;
import com.sesac.gojigi.review.review.dto.request.ReviewImagePresignRequest;
import com.sesac.gojigi.review.review.dto.request.ReviewUpdateRequest;
import com.sesac.gojigi.review.review.dto.response.MyReviewResponse;
import com.sesac.gojigi.review.review.dto.response.ProductRatingResponse;
import com.sesac.gojigi.review.review.dto.response.ProductReviewResponse;
import com.sesac.gojigi.review.review.dto.response.ProductReviewSummaryResponse;
import com.sesac.gojigi.review.review.dto.response.ReviewCreateResponse;
import com.sesac.gojigi.review.review.dto.response.ReviewImagePresignResponse;
import com.sesac.gojigi.review.review.dto.response.ReviewResponse;
import com.sesac.gojigi.review.review.dto.response.StoreRatingResponse;
import com.sesac.gojigi.review.review.dto.response.WritableReviewResponse;
import com.sesac.gojigi.review.review.service.MyReviewQueryService;
import com.sesac.gojigi.review.review.service.ProductReviewQueryService;
import com.sesac.gojigi.review.review.service.RatingQueryService;
import com.sesac.gojigi.review.review.service.ReviewImagePresignService;
import com.sesac.gojigi.review.review.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 구매자 · 공개 리뷰 — 게이트웨이 /api/reviews/** (GET 공개, 그 외 로그인) → review-service.
 * 작성 · 수정 · 삭제 · 마이페이지(1 ~ 5절), 상품 리뷰 목록 · 요약(9절), 여러 상품 · 스토어 평점(10절) — docs/api/review.md.
 * 판매자는 SellerReviewController, admin 내부 호출은 InternalReviewController.
 */
@Tag(name = "리뷰", description = "구매확정한 상품주문의 리뷰 — 작성 · 수정 · 삭제 · 내 리뷰 · 상품 리뷰 · 평점(공개)")
@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewController {

    /** 평점 한 번에 묻는 ID 수 — product 내부 API(product-options) 와 같은 100 */
    private static final int MAX_IDS = 100;

    private final ReviewService reviewService;
    private final MyReviewQueryService myReviewQueryService;
    private final ProductReviewQueryService productReviewQueryService;
    private final RatingQueryService ratingQueryService;
    private final ReviewImagePresignService reviewImagePresignService;

    @Operation(summary = "리뷰 작성", description = """
            인가: 로그인 (작성자 = X-User-Id). 구매확정한 내 상품주문당 1개, 구매확정 후 30일 이내.
            본문은 일반 텍스트 20~2,000자 — 태그는 지우고 저장한다. 사진은 imageKeys 0~3개(업로드 주소 발급으로 받은 내 키).
            에러: 400 INVALID_INPUT(형식) · 400 REVIEW_IMAGE_INVALID(남의 · 형식이 다른 키 · 4장 이상 · 중복) · 401 UNAUTHORIZED ·
            403 REVIEW_NOT_ELIGIBLE(자격 없음 · 남의 상품주문) ·
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

    @Operation(summary = "리뷰 사진 업로드 주소 발급", description = """
            인가: 로그인. 사진마다 images 버킷 presigned PUT 주소를 발급한다(10분, 응답은 요청과 같은 순서).
            1~3개 · image/jpeg · image/png · image/webp · 각 10MB 이하. 키는 reviews/{userId}/{UUID}.{jpg|png|webp}.
            브라우저가 uploadUrl 로 PUT(Content-Type · 크기를 요청과 같게 — 서명에 포함돼 다르면 S3 403) → 받은 key 를 작성 · 수정 imageKeys 로.
            에러: 400 INVALID_INPUT · REVIEW_IMAGE_INVALID("n번째 파일: …", 하나만 틀려도 전체 거절) · 401 UNAUTHORIZED ·
            502 EXTERNAL_API_ERROR(자격 증명)""")
    @PostMapping("/images/presign")
    public ApiResponse<List<ReviewImagePresignResponse>> presignImages(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @Valid @RequestBody ReviewImagePresignRequest request) {
        return ApiResponse.success("업로드 주소를 발급했습니다.", reviewImagePresignService.presign(user.userId(), request));
    }

    @Operation(summary = "내 리뷰 수정", description = """
            인가: 로그인 (작성자 본인). 별점 · 한줄 요약 · 본문 · 사이즈 체감 · 사진을 통째로 바꾼다 — 작성과 같은 규칙, 기한 없음.
            사진은 남길 기존 키 + 새로 발급받은 키를 순서대로(비우면 사진 삭제). 응답은 수정된 값(200). 판매자 답글은 그대로 남는다.
            에러: 400 INVALID_INPUT(형식) · 400 REVIEW_IMAGE_INVALID · 401 UNAUTHORIZED ·
            404 REVIEW_NOT_FOUND(없는 · 삭제된 · 다른 회원의 리뷰) ·
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

    @Operation(summary = "내가 쓴 리뷰", description = """
            인가: 로그인 — GET 은 게이트웨이가 공개로 통과시키므로 X-User-Id 가 없으면 여기서 401.
            삭제한 리뷰 제외, 최신 작성순. 블라인드된 리뷰도 blinded=true 로 보인다(작성자 본인에게만).
            상품명 · 옵션명 · 대표 사진은 구매확정 시점 스냅샷. page(0부터) · size(1~50, 기본 10).
            에러: 400 INVALID_INPUT(page · size) · 401 UNAUTHORIZED""")
    @GetMapping("/me")
    public ApiResponse<PageResponse<MyReviewResponse>> listMine(@Parameter(hidden = true) @CurrentUser AuthUser user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return ApiResponse.success("내가 쓴 리뷰를 조회했습니다.", myReviewQueryService.listMine(user.userId(), page, size));
    }

    @Operation(summary = "작성 가능한 리뷰", description = """
            인가: 로그인 (X-User-Id 없으면 401). 구매확정 후 30일 안 · 아직 리뷰가 없는(삭제한 리뷰가 있으면 제외) 상품주문,
            기한 임박순. 페이징 없음.
            에러: 401 UNAUTHORIZED""")
    @GetMapping("/me/writable")
    public ApiResponse<List<WritableReviewResponse>> listWritable(@Parameter(hidden = true) @CurrentUser AuthUser user) {
        return ApiResponse.success("작성 가능한 리뷰를 조회했습니다.", myReviewQueryService.listWritable(user.userId()));
    }

    @Operation(summary = "상품 리뷰 목록 (공개)", description = """
            인가: 없음(공개). 블라인드 · 삭제 제외. rating 으로 별점 하나만 · photoOnly=true 면 사진 있는 리뷰만(선택),
            sort: LATEST(기본) · RATING_HIGH · RATING_LOW. page(0부터) · size(1~50, 기본 10). 없는 상품은 빈 목록.
            에러: 400 INVALID_INPUT(productId 없음 · rating 1~5 밖 · sort 모르는 값 · page · size)""")
    @GetMapping
    public ApiResponse<PageResponse<ProductReviewResponse>> listForProduct(@RequestParam Long productId,
            @RequestParam(required = false) @Min(1) @Max(5) Integer rating,
            @RequestParam(defaultValue = "false") boolean photoOnly,
            @RequestParam(defaultValue = "LATEST") ProductReviewSort sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return ApiResponse.success("리뷰 목록을 조회했습니다.",
                productReviewQueryService.list(productId, rating, photoOnly, sort, page, size));
    }

    @Operation(summary = "상품 리뷰 요약 (공개)", description = """
            인가: 없음(공개). 평균 · 리뷰 수 · 별점 분포(5→1) · 사이즈 체감 분포(SMALL · TRUE_TO_SIZE · LARGE) — 블라인드 · 삭제 제외.
            없는 상품은 0. 에러: 400 INVALID_INPUT(productId 없음 · 숫자 아님)""")
    @GetMapping("/summary")
    public ApiResponse<ProductReviewSummaryResponse> summarizeForProduct(@RequestParam Long productId) {
        return ApiResponse.success("리뷰 요약을 조회했습니다.", productReviewQueryService.summarize(productId));
    }

    @Operation(summary = "여러 상품 평점 (공개)", description = """
            인가: 없음(공개). ids=101,102 또는 ids=101&ids=102, 1~100개. 요청한 순서대로(중복은 한 번), 리뷰 없는 상품은 0 · 0.
            블라인드 · 삭제 제외. 에러: 400 INVALID_INPUT(ids 없음 · 숫자 아님 · 100개 초과)""")
    @GetMapping("/ratings/products")
    public ApiResponse<List<ProductRatingResponse>> rateProducts(@RequestParam @Size(min = 1, max = MAX_IDS) List<Long> ids) {
        return ApiResponse.success("상품 평점을 조회했습니다.", ratingQueryService.rateProducts(ids));
    }

    @Operation(summary = "여러 스토어 평점 (공개)", description = """
            인가: 없음(공개). ids 1~100개, 요청한 순서대로, 리뷰 없는 스토어는 0 · 0. 블라인드 · 삭제 제외.
            에러: 400 INVALID_INPUT(ids 없음 · 숫자 아님 · 100개 초과)""")
    @GetMapping("/ratings/stores")
    public ApiResponse<List<StoreRatingResponse>> rateStores(@RequestParam @Size(min = 1, max = MAX_IDS) List<Long> ids) {
        return ApiResponse.success("스토어 평점을 조회했습니다.", ratingQueryService.rateStores(ids));
    }
}
