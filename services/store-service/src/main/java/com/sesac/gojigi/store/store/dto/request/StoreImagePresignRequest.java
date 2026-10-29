package com.sesac.gojigi.store.store.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** 대표 이미지 업로드 주소 발급 (docs/api/store.md 13-3절) — 브라우저 file.type · file.size 그대로 */
public record StoreImagePresignRequest(
        @Schema(description = "이미지 형식 — image/jpeg · image/png · image/webp", example = "image/jpeg")
        @NotBlank(message = "이미지 형식을 보내 주세요.")
        @Pattern(regexp = "^image/(jpeg|png|webp)$", message = "대표 이미지는 JPG · PNG · WEBP 만 올릴 수 있습니다.")
        String contentType,

        @Schema(description = "바이트 — 10MB 이하", example = "834512")
        @NotNull(message = "파일 크기를 보내 주세요.")
        @Positive(message = "빈 파일은 올릴 수 없습니다.")
        @Max(value = 10L * 1024 * 1024, message = "대표 이미지는 10MB 이하만 올릴 수 있습니다.")
        Long size) {
}
