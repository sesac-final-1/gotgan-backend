package com.sesac.gojigi.order.order.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 판매자 발주확인 · 배송완료 — 단건 · 일괄(행 선택). 같은 ID 가 두 번 있으면 한 번만 처리 */
public record SellerOrderItemIdsRequest(
        @Schema(description = "상품주문 ID 목록 (한 번에 100개까지)", example = "[456, 457]")
        @NotEmpty @Size(max = 100)
        List<@NotNull @Positive Long> orderItemIds) {
}
