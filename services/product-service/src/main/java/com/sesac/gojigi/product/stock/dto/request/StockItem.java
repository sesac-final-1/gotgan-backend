package com.sesac.gojigi.product.stock.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 옵션 1개의 수량 — 예약 · 해제 요청과 응답이 같이 쓴다 (order StockItem 과 같은 모양) */
public record StockItem(
        @Schema(description = "옵션 ID", example = "11")
        @NotNull(message = "옵션 ID 가 없습니다.")
        Long productOptionId,

        @Schema(description = "수량 (1 이상)", example = "2")
        @NotNull(message = "수량이 없습니다.") @Min(value = 1, message = "수량은 1 이상이어야 합니다.")
        Integer quantity) {
}
