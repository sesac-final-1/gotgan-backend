package com.sesac.gojigi.product.stock.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 재고 해제 (product.md 2절) — sagaId 는 경로에. items 는 예약 때 보낸 목록 그대로:
 * 되돌리는 수량은 product 의 RESERVE 기록 기준이고, items 는 예약 기록이 없을 때 표식을 남길 옵션 · 잠글 옵션을 알기 위해 쓴다.
 */
public record StockReleaseRequest(
        @Schema(description = "예약 때 보낸 옵션 · 수량 그대로")
        @NotEmpty(message = "해제할 상품이 없습니다.")
        List<@Valid @NotNull StockItem> items) {
}
