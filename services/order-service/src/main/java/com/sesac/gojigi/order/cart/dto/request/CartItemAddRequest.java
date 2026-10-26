package com.sesac.gojigi.order.cart.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 장바구니 담기 (ORD-01) — 상품 상세에서 고른 옵션 여러 개를 한 번에. 같은 옵션이 여러 번이면 수량을 합친다 */
public record CartItemAddRequest(
        @Schema(description = "담을 옵션 · 수량 (옵션 없는 상품도 기본 옵션 ID 로)")
        @NotEmpty(message = "담을 상품을 선택해 주세요.") @Size(max = 50, message = "한 번에 50개 옵션까지 담을 수 있습니다.")
        List<@Valid @NotNull Item> items) {

    public record Item(
            @Schema(description = "상품 옵션 ID", example = "11")
            @NotNull @Positive
            Long productOptionId,

            @Schema(description = "수량", example = "2")
            @Positive(message = "수량은 1개 이상이어야 합니다.") @Max(value = 999, message = "수량은 999개 이하로 입력해 주세요.")
            int quantity) {
    }
}
