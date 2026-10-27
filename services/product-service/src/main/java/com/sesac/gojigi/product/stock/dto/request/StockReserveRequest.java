package com.sesac.gojigi.product.stock.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 재고 예약 (product.md 1절) — 같은 옵션 ID 중복은 서비스가 INVALID_INPUT 으로 검사 */
public record StockReserveRequest(
        @Schema(description = "order saga_instance.saga_id — 멱등 키", example = "6c1f0a6e-2b9d-4c1e-9a1f-0b2c3d4e5f60")
        @NotBlank(message = "sagaId 가 없습니다.") @Size(max = 64, message = "sagaId 는 64자 이하여야 합니다.")
        String sagaId,

        @Schema(description = "예약할 옵션 · 수량 — 전부 예약하거나 하나도 안 함")
        @NotEmpty(message = "예약할 상품이 없습니다.")
        List<@Valid @NotNull StockItem> items) {
}
