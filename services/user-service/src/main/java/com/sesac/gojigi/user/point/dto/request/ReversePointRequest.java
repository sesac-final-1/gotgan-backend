package com.sesac.gojigi.user.point.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 취소 · 반품 시 포인트 환원 · 회수 — order 취소 · 반품 Saga 가 호출. 멱등 키 claim-{claimId}-{orderItemId} (point_history.ref_key).
 * restoreAmount = 그 상품주문에 쓴 포인트(order_item.used_point, 스토어 마지막 취소면 배송비를 덮은 포인트 포함),
 * reclaimAmount = 그 상품주문의 적립 포인트(order_item.earned_point) — 실제 적립된 만큼만 회수한다.
 */
public record ReversePointRequest(
        @Schema(description = "order 클레임 ID", example = "789")
        @NotNull @Positive
        Long claimId,

        @Schema(description = "order 상품주문 ID", example = "456")
        @NotNull @Positive
        Long orderItemId,

        @Schema(description = "주문자 회원 ID", example = "4")
        @NotNull @Positive
        Long userId,

        @Schema(description = "주문번호 — 포인트 이력 표시용", example = "20261022-000123")
        @NotBlank @Size(max = 20)
        String orderNo,

        @Schema(description = "돌려줄 사용 포인트 (0 이면 환원 없음)", example = "2000")
        @PositiveOrZero
        long restoreAmount,

        @Schema(description = "회수할 적립 포인트 (0 이면 회수 없음) — 이 상품주문에 실제 적립된 만큼까지만", example = "1500")
        @PositiveOrZero
        long reclaimAmount) {

    /** point_history.ref_key — RESTORE · RECLAIM 이 같은 키를 쓴다 (uk 는 type 과 함께) */
    public String refKey() {
        return "claim-" + claimId + "-" + orderItemId;
    }
}
