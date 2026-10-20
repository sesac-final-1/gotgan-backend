package com.sesac.gojigi.user.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/**
 * 회원가입 요청 — 이메일(= Keycloak username) · 비밀번호 · 이름 · 휴대폰.
 * 비밀번호 규칙은 realm 정책과 같게 먼저 검사한다 (8자 이상 · 영문 · 특수문자). "이메일과 다름" 은 서비스에서, 최종 판단은 Keycloak.
 */
public record SignupRequest(
        @Schema(description = "이메일 (로그인 ID)", example = "newbuyer@gotgan.com")
        @NotBlank(message = "이메일을 입력해 주세요.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 100, message = "이메일은 100자 이하로 입력해 주세요.")
        String email,

        @Schema(description = "비밀번호 — 8자 이상, 영문 · 특수문자 포함, 이메일과 달라야 함")
        @NotBlank(message = "비밀번호를 입력해 주세요.")
        @Size(min = 8, max = 64, message = "비밀번호는 8자 이상 64자 이하로 입력해 주세요.")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*[^A-Za-z0-9]).+$", message = "비밀번호는 영문과 특수문자를 포함해야 합니다.")
        String password,

        @Schema(description = "이름", example = "김곳간")
        @NotBlank(message = "이름을 입력해 주세요.")
        @Size(max = 50, message = "이름은 50자 이하로 입력해 주세요.")
        String name,

        @Schema(description = "휴대폰 번호 — 하이픈 있어도 없어도 됨", example = "010-1234-5678")
        @NotBlank(message = "휴대폰 번호를 입력해 주세요.")
        @Pattern(regexp = "^01[016789]-?\\d{3,4}-?\\d{4}$", message = "휴대폰 번호 형식이 올바르지 않습니다.")
        String phone) {

    /** Keycloak 은 username 을 소문자로 저장한다 — userdb 도 같은 값으로 */
    public String normalizedEmail() {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public String trimmedName() {
        return name.trim();
    }

    /** 010-1234-5678 형식으로 저장 (users.phone 주석) — 10자리는 3-3-4, 11자리는 3-4-4 */
    public String normalizedPhone() {
        String digits = phone.replace("-", "");
        int middleEnd = digits.length() == 10 ? 6 : 7;
        return digits.substring(0, 3) + "-" + digits.substring(3, middleEnd) + "-" + digits.substring(middleEnd);
    }

    @Override
    public String toString() {
        return "SignupRequest[email=" + email + ", password=***, name=" + name + ", phone=" + phone + "]";
    }
}
