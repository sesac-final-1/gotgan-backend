package com.sesac.gojigi.order.order.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 주문서 제출 (ORD-03 · 04) — 장바구니 선택분 또는 바로 구매의 옵션 · 수량 + 배송지 스냅샷 + 사용 포인트.
 * 가격 · 배송비 · 결제 금액은 받지 않는다 — 서버가 product 현재가 · 스토어 배송비 정책으로 다시 계산.
 */
public record OrderCreateRequest(
        @Schema(description = "주문할 옵션 · 수량 (같은 옵션은 한 번만)")
        @NotEmpty(message = "주문할 상품을 선택해 주세요.") @Size(max = 50, message = "한 번에 50개 상품까지 주문할 수 있습니다.")
        List<@Valid @NotNull Item> items,

        @Schema(description = "수령인", example = "김구매")
        @NotBlank(message = "수령인을 입력해 주세요.") @Size(max = 50)
        String receiverName,

        @Schema(description = "수령인 휴대폰 번호 — 하이픈 있어도 없어도 됨", example = "010-1234-5678")
        @NotBlank(message = "수령인 연락처를 입력해 주세요.")
        @Pattern(regexp = "^01[016789]-?\\d{3,4}-?\\d{4}$", message = "휴대폰 번호 형식이 올바르지 않습니다.")
        String receiverPhone,

        @Schema(description = "우편번호", example = "06236")
        @NotBlank(message = "우편번호를 입력해 주세요.") @Size(max = 10)
        String zipcode,

        @Schema(description = "기본 주소", example = "서울 강남구 테헤란로 1")
        @NotBlank(message = "주소를 입력해 주세요.") @Size(max = 200)
        String address1,

        @Schema(description = "상세 주소", example = "101호")
        @Size(max = 200)
        String address2,

        @Schema(description = "배송 요청사항", example = "문 앞에 놓아주세요")
        @Size(max = 200)
        String deliveryMemo,

        @Schema(description = "사용할 포인트 (1P = 1원, 상품 금액 + 배송비까지)", example = "2000")
        @PositiveOrZero(message = "사용 포인트는 0 이상이어야 합니다.")
        long usePoint) {

    public record Item(
            @Schema(description = "상품 옵션 ID", example = "11")
            @NotNull @Positive
            Long productOptionId,

            @Schema(description = "수량", example = "2")
            @Positive(message = "수량은 1개 이상이어야 합니다.") @Max(value = 999, message = "수량은 999개 이하로 입력해 주세요.")
            int quantity) {
    }
}
