package com.sesac.gojigi.order.claim.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 판매자 클레임 거부 — 사유 필수 (스펙 05장, 구매자에게 그대로 보인다) */
public record SellerClaimRejectRequest(
        @Schema(description = "거부 사유", example = "상품 사용 흔적이 있어 반품이 어렵습니다.")
        @NotBlank(message = "거부 사유를 입력해 주세요.") @Size(max = 500)
        String reason) {
}
