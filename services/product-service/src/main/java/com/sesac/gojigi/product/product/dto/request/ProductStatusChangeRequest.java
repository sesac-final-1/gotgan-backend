package com.sesac.gojigi.product.product.dto.request;

import com.sesac.gojigi.product.product.entity.ProductStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 판매중지 · 재개 (product.md 15절) — 한 개든 여러 개든 같은 API. 같은 ID 가 여러 번 오면 한 번만 처리 */
public record ProductStatusChangeRequest(
        @Schema(description = "상품 ID 1~100개", example = "[3, 4, 7]")
        @NotEmpty(message = "상품을 선택해 주세요.") @Size(max = 100, message = "상품은 한 번에 100개까지 바꿀 수 있습니다.")
        List<@NotNull Long> productIds,

        @Schema(description = "ON_SALE(재개) · STOPPED(판매중지)", example = "STOPPED")
        @NotNull(message = "판매 상태를 선택해 주세요.")
        ProductStatus status) {
}
