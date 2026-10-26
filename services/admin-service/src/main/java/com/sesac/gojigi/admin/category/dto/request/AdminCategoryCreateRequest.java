package com.sesac.gojigi.admin.category.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 카테고리 생성 — 상위(parentId 없음)는 판매수수료율 필수: product 생성(사용 중지) → settlement 수수료(오늘부터) → 사용으로 */
public record AdminCategoryCreateRequest(
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
        Integer sortOrder,
        @Schema(description = "판매수수료율 % (상위만, 소수 2자리) — 하위는 무시", example = "11.5")
        @DecimalMin(value = "0", message = "판매수수료율은 0 이상이어야 합니다.")
        @DecimalMax(value = "100", message = "판매수수료율은 100 이하여야 합니다.")
        @Digits(integer = 3, fraction = 2, message = "판매수수료율은 소수 둘째 자리까지 입력해 주세요.")
        BigDecimal commissionRate) {
}
