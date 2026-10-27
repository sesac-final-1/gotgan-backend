package com.sesac.gojigi.order.cart.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 장바구니 줄 수정 — 수량 · 선택 중 보낸 것만 바꾼다 (둘 다 없으면 400) */
public record CartItemUpdateRequest(
        @Schema(description = "수량 (1~999)", example = "3")
        @Min(value = 1, message = "수량은 1개 이상이어야 합니다.") @Max(value = 999, message = "수량은 999개 이하로 입력해 주세요.")
        Integer quantity,

        @Schema(description = "주문할 상품으로 선택", example = "true")
        Boolean checked) {

    @Schema(hidden = true)
    @AssertTrue(message = "바꿀 수량이나 선택 여부를 보내 주세요.")
    public boolean isAnyField() {
        return quantity != null || checked != null;
    }
}
