package com.sesac.gojigi.product.stock.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 재고 복원 (product.md 3절) — 클레임 1건 = 상품주문 1건 = 옵션 1개. 수량은 order 가 클레임 수량 그대로 */
public record StockRestoreRequest(
        @Schema(description = "order 클레임 ID — 멱등 키 (ref_key = claim-{claimId})", example = "789")
        @NotNull(message = "claimId 가 없습니다.")
        Long claimId,

        @Schema(description = "옵션 ID", example = "11")
        @NotNull(message = "옵션 ID 가 없습니다.")
        Long productOptionId,

        @Schema(description = "복원 수량 (1 이상)", example = "1")
        @NotNull(message = "수량이 없습니다.") @Min(value = 1, message = "수량은 1 이상이어야 합니다.")
        Integer quantity) {
}
