package com.sesac.gojigi.user.address.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 배송지 등록 · 수정 — 필드 이름은 API 명세 · 화면과 같게 (Address {label, name, phone, zip, addr, detail, main}) */
public record AddressRequest(
        @Schema(description = "배송지 이름", example = "집")
        @NotBlank(message = "배송지 이름을 입력해 주세요.")
        @Size(max = 30, message = "배송지 이름은 30자 이하로 입력해 주세요.")
        String label,

        @Schema(description = "받는 분", example = "김곳간")
        @NotBlank(message = "받는 분을 입력해 주세요.")
        @Size(max = 50, message = "받는 분은 50자 이하로 입력해 주세요.")
        String name,

        @Schema(description = "받는 분 휴대폰 — 하이픈 있어도 없어도 됨 (배송 알림용)", example = "010-1234-5678")
        @NotBlank(message = "연락처를 입력해 주세요.")
        @Pattern(regexp = "^01[016789]-?\\d{3,4}-?\\d{4}$", message = "휴대폰 번호 형식이 올바르지 않습니다.")
        String phone,

        @Schema(description = "우편번호 (5자리)", example = "06236")
        @NotBlank(message = "우편번호를 입력해 주세요.")
        @Pattern(regexp = "^\\d{5}$", message = "우편번호는 숫자 5자리입니다.")
        String zip,

        @Schema(description = "기본 주소", example = "서울특별시 강남구 테헤란로 152")
        @NotBlank(message = "주소를 입력해 주세요.")
        @Size(max = 200, message = "주소는 200자 이하로 입력해 주세요.")
        String addr,

        @Schema(description = "상세 주소 (선택)", example = "21층")
        @Size(max = 200, message = "상세 주소는 200자 이하로 입력해 주세요.")
        String detail,

        @Schema(description = "기본 배송지로 지정 — 첫 배송지는 false 여도 기본이 된다")
        boolean main) {

    /** 010-1234-5678 형식으로 저장 — 10자리는 3-3-4, 11자리는 3-4-4 */
    public String normalizedPhone() {
        String digits = phone.replace("-", "");
        int middleEnd = digits.length() == 10 ? 6 : 7;
        return digits.substring(0, 3) + "-" + digits.substring(3, middleEnd) + "-" + digits.substring(middleEnd);
    }

    /** 빈 상세 주소는 null */
    public String trimmedDetail() {
        if (detail == null) {
            return null;
        }
        String trimmed = detail.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
