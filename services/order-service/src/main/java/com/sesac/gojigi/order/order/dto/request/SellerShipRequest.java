package com.sesac.gojigi.order.order.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 판매자 송장 등록 (DLV-02) — 단건 · 일괄 모달. 송장번호를 비우면 서버가 mock 번호를 만든다 (외부 택배 API 없음) */
public record SellerShipRequest(
        @Schema(description = "상품주문별 택배사 · 송장번호 (한 번에 100개까지)")
        @NotEmpty @Size(max = 100)
        List<@Valid @NotNull Item> items) {

    public record Item(
            @Schema(description = "상품주문 ID", example = "456")
            @NotNull @Positive
            Long orderItemId,

            @Schema(description = "택배사", example = "CJ대한통운")
            @NotBlank(message = "택배사를 선택해 주세요.") @Size(max = 30)
            String courier,

            @Schema(description = "송장번호 — 비우면 mock 번호 (택배사 접두 + 상품주문 ID)", example = "651200000456")
            @Pattern(regexp = "^[0-9-]{6,40}$", message = "송장번호는 숫자와 - 로 6~40자입니다.")
            String trackingNo) {
    }
}
