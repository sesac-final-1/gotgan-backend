package com.sesac.gojigi.review.review.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 관리자 블라인드 · 해제 (review.md 8-2, 신고 B안 4-2) — blinded=true 면 reason 필수(서비스에서 검사), 해제는 생략 가능 */
public record ReviewBlindRequest(
        @Schema(description = "true 블라인드 · false 해제", example = "true")
        @NotNull(message = "블라인드 여부를 지정해 주세요.")
        Boolean blinded,

        @Schema(description = "블라인드 사유 — 블라인드면 필수, 255자 이하. 해제 때는 저장하지 않음", example = "욕설 · 비방")
        @Size(max = 255, message = "블라인드 사유는 255자 이하로 입력해 주세요.")
        String reason) {
}
