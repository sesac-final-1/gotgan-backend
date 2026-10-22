package com.sesac.gojigi.user.point.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 포인트 사용 — order Saga 가 주문 생성 때 호출. sagaId 가 멱등 키 (point_history.ref_key) */
public record UsePointRequest(
        @Schema(description = "주문 Saga ID — 같은 값으로 다시 오면 처음 결과를 그대로", example = "saga-20261022-000123")
        @NotBlank @Size(max = 64)
        String sagaId,

        @Schema(description = "주문자 회원 ID", example = "4")
        @NotNull @Positive
        Long userId,

        @Schema(description = "주문번호 — 포인트 이력 표시용", example = "20261022-000123")
        @NotBlank @Size(max = 20)
        String orderNo,

        @Schema(description = "사용할 포인트 (1P = 1원, 1 이상)", example = "1500")
        @Positive
        long amount) {
}
