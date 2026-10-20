package com.sesac.gojigi.user.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/** 이메일 중복 확인 — GET /api/auth/email-availability?email= */
public record EmailAvailabilityRequest(
        @Schema(description = "확인할 이메일", example = "newbuyer@gotgan.com")
        @NotBlank(message = "이메일을 입력해 주세요.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 100, message = "이메일은 100자 이하로 입력해 주세요.")
        String email) {

    public String normalizedEmail() {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
