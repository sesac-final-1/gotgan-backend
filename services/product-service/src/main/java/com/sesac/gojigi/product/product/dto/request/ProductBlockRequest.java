package com.sesac.gojigi.product.product.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 강제 판매중지 · 해제 (6-2절) — blocked=true 면 reason 필수(빈 값은 서비스가 PRODUCT_BLOCK_REASON_REQUIRED) */
public record ProductBlockRequest(
        @Schema(description = "true = 강제 판매중지, false = 해제", example = "true")
        @NotNull(message = "판매중지 여부를 입력해 주세요.")
        Boolean blocked,

        @Schema(description = "사유 (blocked=true 면 필수)", example = "상품 정보 고시 누락 — 원산지 표기 필요")
        @Size(max = 255, message = "사유는 255자 이하로 입력해 주세요.")
        String reason) {
}
