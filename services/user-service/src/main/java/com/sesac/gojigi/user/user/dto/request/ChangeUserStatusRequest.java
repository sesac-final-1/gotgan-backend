package com.sesac.gojigi.user.user.dto.request;

import com.sesac.gojigi.user.user.entity.status.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 관리자 이용정지 · 해제. 사유는 필수지만 빈 값 검사는 엔티티가 USER_STATUS_REASON_REQUIRED 로 (INVALID_INPUT 과 구분).
 */
public record ChangeUserStatusRequest(
        @Schema(description = "SUSPENDED = 이용정지, ACTIVE = 해제", example = "SUSPENDED")
        @NotNull(message = "변경할 상태를 선택해 주세요.")
        UserStatus status,

        @Schema(description = "정지 · 해제 사유 (필수)", example = "허위 리뷰 / 리뷰 어뷰징")
        @Size(max = 255, message = "사유는 255자 이하로 입력해 주세요.")
        String reason,

        @Schema(description = "정지 기간(일) — 생략하면 영구 정지. 해제(ACTIVE)에서는 무시", example = "7")
        @Min(value = 1, message = "정지 기간은 1일 이상입니다.")
        @Max(value = 365, message = "정지 기간은 365일 이하입니다.")
        Integer days) {

    public String trimmedReason() {
        return reason == null ? null : reason.strip();
    }
}
