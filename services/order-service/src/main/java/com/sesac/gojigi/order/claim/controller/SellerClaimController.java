package com.sesac.gojigi.order.claim.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.order.claim.dto.request.ClaimStage;
import com.sesac.gojigi.order.claim.dto.request.SellerClaimRejectRequest;
import com.sesac.gojigi.order.claim.dto.request.SellerClaimReshipRequest;
import com.sesac.gojigi.order.claim.dto.response.ClaimDetailResponse;
import com.sesac.gojigi.order.claim.dto.response.SellerClaimListResponse;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import com.sesac.gojigi.order.claim.service.ClaimQueryService;
import com.sesac.gojigi.order.claim.service.SellerClaimTxService;
import com.sesac.gojigi.order.saga.service.CancelOrderItemSaga;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

/** 판매자 클레임 처리 (CLM-05) — 게이트웨이가 SELLER 역할을 확인하고 X-Store-Id 로 본인 스토어를 알려 준다 */
@Tag(name = "판매자 클레임", description = "취소 · 반품 · 교환 처리 — 인가: 판매자 (X-Store-Id 본인 스토어만)")
@RestController
@RequestMapping("/api/seller/claims")
@RequiredArgsConstructor
public class SellerClaimController {

    private static final String ERRORS = """
            에러: 404 CLAIM_NOT_FOUND(없음 · 남의 스토어) · 409 CLAIM_INVALID_STATUS(지금 상태 · 유형에서 할 수 없음) · 403 FORBIDDEN(스토어 없음)""";

    private final ClaimQueryService claimQueryService;
    private final SellerClaimTxService sellerClaimTx;
    private final CancelOrderItemSaga cancelOrderItemSaga;

    @Operation(summary = "판매자 클레임 목록", description = """
            stage: OPEN(처리대기 — REQUESTED · APPROVED · COLLECTED · RESHIPPED) · DONE(처리완료 — COMPLETED · REJECTED), type: 유형(없으면 전체).
            최신 요청 먼저, page 0부터 · size 기본 20 · 최대 100. stageCounts 는 고른 유형 기준, typeCounts 는 고른 단계 기준.""")
    @GetMapping
    public ApiResponse<SellerClaimListResponse> list(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                     @RequestParam(defaultValue = "OPEN") ClaimStage stage,
                                                     @RequestParam(required = false) ClaimType type,
                                                     @RequestParam(defaultValue = "0") @Min(0) int page,
                                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success("클레임 목록을 조회했습니다.",
                claimQueryService.listSeller(user.storeId(), stage, type, page, size));
    }

    @Operation(summary = "판매자 클레임 상세", description = ERRORS)
    @GetMapping("/{claimId}")
    public ApiResponse<ClaimDetailResponse> get(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                @PathVariable Long claimId) {
        return ApiResponse.success("클레임을 조회했습니다.", claimQueryService.getSeller(user.storeId(), claimId));
    }

    @Operation(summary = "승인 (반품 · 교환 → 수거 요청)", description = "REQUESTED → APPROVED. 회수 후 수거 완료를 눌러 주세요.\n" + ERRORS)
    @PatchMapping("/{claimId}/approve")
    public ApiResponse<ClaimDetailResponse> approve(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                    @PathVariable Long claimId) {
        return ApiResponse.success("요청을 승인했습니다. 상품을 회수해 주세요.", sellerClaimTx.approve(user.storeId(), claimId));
    }

    @Operation(summary = "거부 (사유 필수)", description = "REQUESTED → REJECTED, 상품주문은 다시 배송완료.\n" + ERRORS)
    @PatchMapping("/{claimId}/reject")
    public ApiResponse<ClaimDetailResponse> reject(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                   @PathVariable Long claimId,
                                                   @Valid @RequestBody SellerClaimRejectRequest request) {
        return ApiResponse.success("요청을 거부했습니다.",
                sellerClaimTx.reject(user.storeId(), user.userId(), claimId, request.reason()));
    }

    @Operation(summary = "수거 완료", description = """
            APPROVED → COLLECTED. 반품은 이어서 환불(결제 부분 취소 → 재고 복원 → 포인트 환원 · 회수) — 끝나면 COMPLETED(상품주문 RETURNED),
            결제 취소가 막히면 COLLECTED 로 남고 자동으로 마저 진행. 교환은 이어서 재배송 송장.
            """ + ERRORS)
    @PatchMapping("/{claimId}/collect")
    public ApiResponse<ClaimDetailResponse> collect(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                    @PathVariable Long claimId) {
        ClaimDetailResponse claim = claimQueryService.getSeller(user.storeId(), claimId);
        if (claim.type() == ClaimType.RETURN) {
            cancelOrderItemSaga.collectReturn(user.storeId(), claimId);
            return ApiResponse.success("수거 완료 — 환불을 진행했습니다.", claimQueryService.getSeller(user.storeId(), claimId));
        }
        return ApiResponse.success("수거 완료 — 재배송 송장을 입력해 주세요.", sellerClaimTx.collectExchange(user.storeId(), claimId));
    }

    @Operation(summary = "교환 재배송 송장", description = """
            COLLECTED → RESHIPPED. 송장번호를 비우면 mock 번호. 재배송의 배송완료는 주문 관리의 배송완료 버튼 · 자동 배송완료가 처리하고
            그때 교환완료(COMPLETED · 상품주문 다시 배송완료).
            """ + ERRORS)
    @PatchMapping("/{claimId}/reship")
    public ApiResponse<ClaimDetailResponse> reship(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                   @PathVariable Long claimId,
                                                   @Valid @RequestBody SellerClaimReshipRequest request) {
        return ApiResponse.success("재배송 송장을 등록했습니다.", sellerClaimTx.reship(user.storeId(), user.userId(), claimId,
                request.courier(), request.trackingNo()));
    }
}
