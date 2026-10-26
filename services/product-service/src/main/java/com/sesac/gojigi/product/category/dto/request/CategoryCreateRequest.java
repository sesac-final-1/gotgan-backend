package com.sesac.gojigi.product.category.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 카테고리 생성 — parentId 없으면 상위(사용 중지로 생성), 있으면 그 상위의 하위 */
public record CategoryCreateRequest(
        @Schema(description = "상위 카테고리 ID — 상위를 만들 때는 생략", example = "3")
        Long parentId,
        @Schema(description = "카테고리 이름", example = "셔츠")
        @NotBlank(message = "카테고리 이름을 입력해 주세요.") @Size(max = 50, message = "카테고리 이름은 50자 이하로 입력해 주세요.")
        String name,
        @Schema(description = "영문명 (선택)", example = "Shirts")
        @Size(max = 50, message = "영문명은 50자 이하로 입력해 주세요.")
        String nameEn,
        @Schema(description = "정렬 순서 — 생략하면 같은 부모 안 맨 뒤", example = "1")
        @PositiveOrZero(message = "정렬 순서는 0 이상이어야 합니다.")
        Integer sortOrder) {
}
