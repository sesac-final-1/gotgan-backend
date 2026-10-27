package com.sesac.gojigi.order.claim.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 교환 재배송 송장 (CLM-04) — 송장번호를 비우면 서버가 mock 번호 (발주 송장 등록과 같은 규칙) */
public record SellerClaimReshipRequest(
        @Schema(description = "택배사", example = "CJ대한통운")
        @NotBlank(message = "택배사를 선택해 주세요.") @Size(max = 30)
        String courier,

        @Schema(description = "송장번호 — 비우면 mock 번호", example = "651200000456")
        @Pattern(regexp = "^[0-9-]{6,40}$", message = "송장번호는 숫자와 - 로 6~40자입니다.")
        String trackingNo) {
}
