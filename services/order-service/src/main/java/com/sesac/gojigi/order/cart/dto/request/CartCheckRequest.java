package com.sesac.gojigi.order.cart.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 장바구니 선택 한꺼번에 — 전체 선택 · 해제(productOptionIds 없음) 또는 스토어 묶음 선택 */
public record CartCheckRequest(
        @Schema(description = "선택 여부", example = "true")
        @NotNull
        Boolean checked,

        @Schema(description = "바꿀 옵션 ID — 없으면 장바구니 전체", example = "[11, 12]")
        @Size(max = 100)
        List<@NotNull @Positive Long> productOptionIds) {
}
