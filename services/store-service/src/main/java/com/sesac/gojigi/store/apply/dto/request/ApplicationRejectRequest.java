package com.sesac.gojigi.store.apply.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 입점 반려 요청 (docs/api/store.md 6절) — 사유는 신청자 화면 · 반려 메일에 그대로 보인다 */
public record ApplicationRejectRequest(
        @Schema(description = "반려 사유 (필수, 255자 이하)", example = "사업자등록증 정보가 신청서와 다릅니다")
        @NotBlank(message = "반려 사유를 입력해 주세요.")
        @Size(max = 255, message = "반려 사유는 255자 이하로 입력해 주세요.")
        String reason) {
}
