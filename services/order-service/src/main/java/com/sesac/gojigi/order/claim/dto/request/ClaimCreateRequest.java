package com.sesac.gojigi.order.claim.dto.request;

import com.sesac.gojigi.order.claim.entity.ClaimReasonCode;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 취소 · 반품 · 교환 요청 (CLM-01 ~ 04) — 상품주문 1건 단위. 지금은 결제완료 단계 취소(CANCEL)만 받는다 */
public record ClaimCreateRequest(
        @Schema(description = "상품주문 ID (주문 상세 items[].orderItemId)", example = "456")
        @NotNull @Positive
        Long orderItemId,

        @Schema(description = "유형 — CANCEL(취소) · RETURN(반품) · EXCHANGE(교환). 지금은 CANCEL 만", example = "CANCEL")
        @NotNull
        ClaimType type,

        @Schema(description = "사유 — CHANGE_OF_MIND · DEFECTIVE · WRONG_DELIVERY · DELAYED · ETC", example = "CHANGE_OF_MIND")
        @NotNull
        ClaimReasonCode reasonCode,

        @Schema(description = "상세 사유", example = "다른 옵션으로 다시 주문할게요")
        @Size(max = 500)
        String reasonDetail) {
}
