package com.sesac.gojigi.store.store.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 판매자 스토어 설정 수정 (docs/api/store.md 13절) — PUT 이라 설정 전체를 보낸다(조회 응답 값을 고쳐 그대로).
 * 대표 이미지 키는 서비스가 검사(없음 · 지금 키 · 내 stores/{storeId}/ 키만), 스토어명 · 주소는 바꾸지 않음
 */
public record SellerStoreUpdateRequest(
        @Schema(description = "스토어 소개 HTML (선택, 5000자 이하) — 저장 전 정화", example = "<p>리넨 의류를 만듭니다.</p>")
        @Size(max = 5000, message = "스토어 소개는 5000자 이하로 입력해 주세요.")
        String intro,

        @Schema(description = "대표 이미지 S3 키 — 없으면 null, 지금 키를 그대로 보내면 유지, 새 이미지는 업로드 주소 발급으로 받은 stores/{storeId}/ 키",
                example = "stores/1/7f3c9e2a.jpg")
        @Size(max = 255, message = "이미지 키가 너무 깁니다.")
        String imageKey,

        @Schema(description = "표시 색 #RRGGBB (화면은 6색 중 선택)", example = "#5b6b4a")
        @NotBlank(message = "표시 색을 선택해 주세요.")
        @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "표시 색은 #RRGGBB 형식입니다.")
        String color,

        @Schema(description = "고객 문의 연락처 — 휴대 · 지역 · 070 번호", example = "02-0000-1234")
        @NotBlank(message = "고객 문의 연락처를 입력해 주세요.")
        @Pattern(regexp = "^0\\d{1,2}-\\d{3,4}-\\d{4}$", message = "연락처는 02-0000-0000 · 010-0000-0000 형식입니다.")
        String csPhone,

        @Schema(description = "기본 배송비 (원, 0 이면 무료)", example = "3000")
        @NotNull(message = "기본 배송비를 입력해 주세요.")
        @PositiveOrZero(message = "기본 배송비는 0원 이상입니다.")
        @Max(value = 100_000, message = "기본 배송비는 100,000원 이하입니다.")
        Long shippingFee,

        @Schema(description = "무료배송 기준 금액 (원) — null 이면 무료배송 없음", example = "50000")
        @Positive(message = "무료배송 기준 금액은 1원 이상입니다. 없으면 비워 주세요.")
        @Max(value = 10_000_000, message = "무료배송 기준 금액은 10,000,000원 이하입니다.")
        Long freeShippingThreshold,

        @Schema(description = "기본 택배사 (선택)", example = "CJ대한통운")
        @Size(max = 30, message = "택배사는 30자 이하로 입력해 주세요.")
        String courier,

        @Schema(description = "출고 안내 문구 (선택)", example = "평일 14시 이전 결제 시 당일 출고")
        @Size(max = 100, message = "출고 안내는 100자 이하로 입력해 주세요.")
        String dispatchNote) {
}
