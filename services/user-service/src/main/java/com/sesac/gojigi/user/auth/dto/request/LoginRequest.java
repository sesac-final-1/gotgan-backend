package com.sesac.gojigi.user.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** 로그인 요청 — 이메일 = Keycloak username */
public record LoginRequest(
        @Schema(description = "이메일 (= Keycloak username)", example = "wlsgksvheh+seller1@gmail.com")
        @NotBlank(message = "이메일을 입력해 주세요.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @Schema(description = "비밀번호")
        @NotBlank(message = "비밀번호를 입력해 주세요.")
        String password) {
}
