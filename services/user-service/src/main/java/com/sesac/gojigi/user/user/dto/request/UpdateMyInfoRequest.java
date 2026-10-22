package com.sesac.gojigi.user.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 내 정보 수정 — 보낸 항목만 바꾼다 (null = 그대로). 이름 · 휴대폰 규칙은 회원가입(SignupRequest)과 같다.
 * 닉네임은 빈 문자열이면 지운다.
 */
public record UpdateMyInfoRequest(
        @Schema(description = "이름 (생략 시 그대로)", example = "김곳간")
        @Size(max = 50, message = "이름은 50자 이하로 입력해 주세요.")
        @Pattern(regexp = ".*\\S.*", message = "이름을 입력해 주세요.")
        String name,

        @Schema(description = "닉네임 (생략 시 그대로, 빈 문자열이면 삭제)", example = "곳간지기")
        @Size(max = 30, message = "닉네임은 30자 이하로 입력해 주세요.")
        String nick,

        @Schema(description = "휴대폰 번호 — 하이픈 있어도 없어도 됨 (생략 시 그대로)", example = "010-1234-5678")
        @Pattern(regexp = "^01[016789]-?\\d{3,4}-?\\d{4}$", message = "휴대폰 번호 형식이 올바르지 않습니다.")
        String phone) {

    public String trimmedName() {
        return name == null ? null : name.trim();
    }

    /** 빈 문자열은 null(삭제) */
    public String trimmedNick() {
        if (nick == null) {
            return null;
        }
        String trimmed = nick.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 010-1234-5678 형식으로 저장 (users.phone 주석) — 10자리는 3-3-4, 11자리는 3-4-4 */
    public String normalizedPhone() {
        if (phone == null) {
            return null;
        }
        String digits = phone.replace("-", "");
        int middleEnd = digits.length() == 10 ? 6 : 7;
        return digits.substring(0, 3) + "-" + digits.substring(3, middleEnd) + "-" + digits.substring(middleEnd);
    }
}
