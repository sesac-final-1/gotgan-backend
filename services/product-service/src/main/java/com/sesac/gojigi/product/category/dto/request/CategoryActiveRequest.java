package com.sesac.gojigi.product.category.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** 사용 / 사용 중지 */
public record CategoryActiveRequest(
        @Schema(description = "사용 여부", example = "true")
        @NotNull(message = "사용 여부를 입력해 주세요.")
        Boolean active) {
}
