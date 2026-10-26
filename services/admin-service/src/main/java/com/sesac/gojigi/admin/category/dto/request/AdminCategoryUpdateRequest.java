package com.sesac.gojigi.admin.category.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 카테고리 수정 — 이름 · 영문명 · 정렬은 product PUT, 사용 여부가 바뀌면 product PATCH 로 따로 (상위를 켤 때는 수수료 확인) */
public record AdminCategoryUpdateRequest(
        @Schema(description = "카테고리 이름", example = "셔츠")
        @NotBlank(message = "카테고리 이름을 입력해 주세요.") @Size(max = 50, message = "카테고리 이름은 50자 이하로 입력해 주세요.")
        String name,
        @Schema(description = "영문명 (선택 — 비우면 지움)", example = "Shirts")
        @Size(max = 50, message = "영문명은 50자 이하로 입력해 주세요.")
        String nameEn,
        @Schema(description = "정렬 순서", example = "1")
        @NotNull(message = "정렬 순서를 입력해 주세요.") @PositiveOrZero(message = "정렬 순서는 0 이상이어야 합니다.")
        Integer sortOrder,
        @Schema(description = "사용 여부 — 생략하면 그대로", example = "true")
        Boolean active) {
}
