package com.sesac.gojigi.user.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 변경 — 현재 비밀번호로 본인 확인 후 새 비밀번호로.
 * 새 비밀번호 규칙은 회원가입과 같게 먼저 검사한다 (8자 이상 · 영문 · 특수문자). 최종 판단은 Keycloak 비밀번호 정책.
 */
public record ChangePasswordRequest(
        @Schema(description = "현재 비밀번호")
        @NotBlank(message = "현재 비밀번호를 입력해 주세요.")
        String currentPassword,

        @Schema(description = "새 비밀번호 — 8자 이상, 영문 · 특수문자 포함, 이메일과 달라야 함")
        @NotBlank(message = "새 비밀번호를 입력해 주세요.")
        @Size(min = 8, max = 64, message = "비밀번호는 8자 이상 64자 이하로 입력해 주세요.")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*[^A-Za-z0-9]).+$", message = "비밀번호는 영문과 특수문자를 포함해야 합니다.")
        String newPassword) {

    @Override
    public String toString() {
        return "ChangePasswordRequest[currentPassword=***, newPassword=***]";
    }
}
