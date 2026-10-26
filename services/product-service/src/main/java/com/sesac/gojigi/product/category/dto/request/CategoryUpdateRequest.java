package com.sesac.gojigi.product.category.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 카테고리 수정 — 이름 · 영문명 · 정렬만. 부모 변경(상위 · 하위 이동)은 없음 */
public record CategoryUpdateRequest(
        @Schema(description = "카테고리 이름", example = "셔츠")
        @NotBlank(message = "카테고리 이름을 입력해 주세요.") @Size(max = 50, message = "카테고리 이름은 50자 이하로 입력해 주세요.")
        String name,
        @Schema(description = "영문명 (선택 — 비우면 지움)", example = "Shirts")
        @Size(max = 50, message = "영문명은 50자 이하로 입력해 주세요.")
        String nameEn,
        @Schema(description = "정렬 순서", example = "1")
        @NotNull(message = "정렬 순서를 입력해 주세요.") @PositiveOrZero(message = "정렬 순서는 0 이상이어야 합니다.")
        Integer sortOrder) {
}
