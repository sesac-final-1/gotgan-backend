package com.sesac.gojigi.order.claim.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.order.claim.dto.request.ClaimCreateRequest;
import com.sesac.gojigi.order.claim.dto.response.ClaimDetailResponse;
import com.sesac.gojigi.order.claim.dto.response.ClaimPreviewResponse;
import com.sesac.gojigi.order.claim.dto.response.ClaimResponse;
import com.sesac.gojigi.order.claim.entity.ClaimReasonCode;
import com.sesac.gojigi.order.claim.entity.ClaimStatus;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import com.sesac.gojigi.order.claim.service.ClaimQueryService;
import com.sesac.gojigi.order.claim.service.ClaimService;
import com.sesac.gojigi.order.saga.service.CancelOrderItemSaga;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 구매자 취소 · 반품 · 교환 — 게이트웨이가 JWT 를 검증하고 X-User-Id 로 본인을 알려 준다 */
@Tag(name = "클레임", description = "취소 · 반품 · 교환 — 인가: 로그인 회원 (X-User-Id)")
@RestController
@RequiredArgsConstructor
public class ClaimController {

    private final CancelOrderItemSaga cancelOrderItemSaga;
    private final ClaimService claimService;
    private final ClaimQueryService claimQueryService;

    @Operation(summary = "내 클레임 내역", description = """
            취소 · 반품 · 교환 신청 전체 — 최신 요청 먼저. status: REQUESTED(판매자 확인 중) · APPROVED(승인 · 수거 요청 / 취소는 환불 중) ·
            COLLECTED(수거 완료) · RESHIPPED(교환 재배송 중) · COMPLETED · REJECTED(rejectReason 에 판매자 사유).""")
    @GetMapping("/api/claims")
    public ApiResponse<List<ClaimDetailResponse>> listMine(@Parameter(hidden = true) @CurrentUser AuthUser user) {
        return ApiResponse.success("클레임 내역을 조회했습니다.", claimQueryService.listMine(user.userId()));
    }

    @Operation(summary = "클레임 요청 (취소 · 반품 · 교환)", description = """
            CANCEL(CLM-01): 결제완료(PAYMENT_DONE) 상품주문을 판매자 승인 없이 즉시 취소 — 결제 부분 취소 → 재고 복원 → 포인트 환원 · 회수.
            응답 status COMPLETED 면 끝, APPROVED 면 환불 처리 중(결제 취소 미확정 · 장애) — 자동으로 마저 진행한다.
            RETURN(CLM-03) · EXCHANGE(CLM-04): 배송완료 후 7일 이내 · 구매확정 전. 응답 status REQUESTED — 판매자 승인 · 수거 뒤 환불 · 재배송.
            반품 환불 = 상품주문 금액 - 사용 포인트 - 반품 배송비(불량 · 오배송이 아니면 스토어 기본 배송비 × 2), 교환은 금액 0.
            에러: 400 INVALID_INPUT / 404 ORDER_NOT_FOUND · CLAIM_ITEM_NOT_FOUND /
            409 CLAIM_NOT_CANCELLABLE(결제완료가 아님) · CLAIM_NOT_RETURNABLE(배송완료 7일 이내가 아님) · CLAIM_IN_PROGRESS(진행 중 요청 있음)""")
    @PostMapping("/api/orders/{orderNo}/claims")
    public ResponseEntity<ApiResponse<ClaimResponse>> create(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                             @PathVariable String orderNo,
                                                             @Valid @RequestBody ClaimCreateRequest request) {
        ClaimResponse claim = request.type() == ClaimType.CANCEL
                ? cancelOrderItemSaga.request(user.userId(), orderNo, request)
                : claimService.requestReturnOrExchange(user.userId(), orderNo, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(message(claim), claim));
    }

    @Operation(summary = "클레임 환불 예정액", description = """
            신청 화면(/mypage/claim/:lineId)의 사유별 배송비 부담 · 환불 예정액. 신청할 수 없는 상품주문이면 신청과 같은 오류.
            shippingFee: 취소는 돌려받는 배송비(+), 반품은 빼는 반품 배송비(-), 교환은 0. sellerFault: 판매자 귀책(불량 · 오배송)이라 배송비 면제.
            에러: 404 ORDER_NOT_FOUND · CLAIM_ITEM_NOT_FOUND / 409 CLAIM_NOT_CANCELLABLE · CLAIM_NOT_RETURNABLE · CLAIM_IN_PROGRESS""")
    @GetMapping("/api/orders/{orderNo}/claims/preview")
    public ApiResponse<ClaimPreviewResponse> preview(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                     @PathVariable String orderNo, @RequestParam Long orderItemId,
                                                     @RequestParam ClaimType type,
                                                     @RequestParam ClaimReasonCode reasonCode) {
        return ApiResponse.success("환불 예정액을 계산했습니다.",
                claimService.preview(user.userId(), orderNo, orderItemId, type, reasonCode));
    }

    private static String message(ClaimResponse claim) {
        if (claim.type() == ClaimType.CANCEL) {
            return claim.status() == ClaimStatus.COMPLETED ? "주문을 취소했습니다." : "취소를 접수했습니다. 환불을 처리하고 있어요.";
        }
        return claim.type() == ClaimType.RETURN ? "반품을 신청했습니다. 판매자 확인 후 회수해 드려요."
                : "교환을 신청했습니다. 판매자 확인 후 회수해 드려요.";
    }
}
