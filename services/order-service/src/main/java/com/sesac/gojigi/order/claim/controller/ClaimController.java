package com.sesac.gojigi.order.claim.controller;

import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.order.claim.dto.request.ClaimCreateRequest;
import com.sesac.gojigi.order.claim.dto.response.ClaimResponse;
import com.sesac.gojigi.order.claim.entity.ClaimStatus;
import com.sesac.gojigi.order.saga.service.CancelOrderItemSaga;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 구매자 취소 · 반품 · 교환 — 게이트웨이가 JWT 를 검증하고 X-User-Id 로 본인을 알려 준다 */
@Tag(name = "클레임", description = "취소 · 반품 · 교환 — 인가: 로그인 회원 (X-User-Id)")
@RestController
@RequiredArgsConstructor
public class ClaimController {

    private final CancelOrderItemSaga cancelOrderItemSaga;

    @Operation(summary = "클레임 요청 (지금은 결제완료 단계 즉시 취소)", description = """
            CLM-01: 결제완료(PAYMENT_DONE) 상품주문을 판매자 승인 없이 즉시 취소 — 결제 부분 취소 → 재고 복원 → 포인트 환원 · 회수.
            환불 = 상품주문 금액 - 사용 포인트(+ 그 스토어 마지막 상품주문이면 배송비)를 카드 등으로, 사용 포인트는 포인트로.
            응답 status COMPLETED 면 끝, APPROVED 면 접수됐고 환불 처리 중(결제 취소 미확정 · 장애) — 자동으로 마저 진행한다.
            에러: 400 INVALID_INPUT · CLAIM_TYPE_NOT_SUPPORTED(반품 · 교환은 아직) / 404 ORDER_NOT_FOUND · CLAIM_ITEM_NOT_FOUND /
            409 CLAIM_NOT_CANCELLABLE(결제완료가 아님) · CLAIM_IN_PROGRESS(진행 중 요청 있음)""")
    @PostMapping("/api/orders/{orderNo}/claims")
    public ResponseEntity<ApiResponse<ClaimResponse>> create(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                             @PathVariable String orderNo,
                                                             @Valid @RequestBody ClaimCreateRequest request) {
        ClaimResponse claim = cancelOrderItemSaga.request(user.userId(), orderNo, request);
        String message = claim.status() == ClaimStatus.COMPLETED ? "주문을 취소했습니다."
                : "취소를 접수했습니다. 환불을 처리하고 있어요.";
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(message, claim));
    }
}
