package com.sesac.gojigi.product.product.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 재고 수정 (product.md 14절) — 화면에서 본 재고(seenStock)를 같이 보낸다. 같은 옵션 중복은 서비스가 INVALID_INPUT 으로 검사 */
public record ProductStockUpdateRequest(
        @Schema(description = "바꿀 옵션 조합 · 재고")
        @NotEmpty(message = "바꿀 재고가 없습니다.")
        List<@Valid @NotNull Item> items) {

    public record Item(
            @Schema(description = "옵션 조합 ID", example = "11")
            @NotNull(message = "옵션 ID 가 없습니다.")
            Long productOptionId,

            @Schema(description = "새 재고 (0 이상 — 0 이면 품절로 보임)", example = "50")
            @NotNull(message = "재고를 입력해 주세요.") @Min(value = 0, message = "재고는 0 이상이어야 합니다.")
            Integer stock,

            @Schema(description = "화면에서 본 재고 — 지금 재고와 다르면 409 STOCK_CHANGED", example = "42")
            @NotNull(message = "화면에서 본 재고가 없습니다.") @Min(value = 0, message = "재고는 0 이상이어야 합니다.")
            Integer seenStock) {
    }
}
