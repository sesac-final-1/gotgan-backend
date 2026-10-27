package com.sesac.gojigi.product.product.dto.request;

import com.sesac.gojigi.product.product.entity.ProductStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 상품 등록 · 수정 (product.md 13절) — 옵션 · 이미지를 통째로 보낸다. 같은 본문을 PUT 에도 쓴다(status 는 수정 때 무시).
 * 형식 오류는 여기서 INVALID_INPUT, 옵션 · 이미지 규칙은 서비스가 PRODUCT_OPTION_INVALID · PRODUCT_IMAGE_INVALID 로 본다 (계약의 코드 구분).
 */
public record ProductCreateRequest(
        @Schema(description = "사용 중인 하위 카테고리 ID", example = "301")
        @NotNull(message = "카테고리를 선택해 주세요.")
        Long categoryId,
        @Schema(description = "상품명", example = "프렌치 리넨 오버핏 셔츠")
        @NotBlank(message = "상품명을 입력해 주세요.") @Size(max = 100, message = "상품명은 100자 이하로 입력해 주세요.")
        String name,
        @Schema(description = "정가 (원)", example = "89000")
        @NotNull(message = "가격을 입력해 주세요.") @Min(value = 100, message = "가격은 100원 이상이어야 합니다.")
        Long price,
        @Schema(description = "즉시할인율 % (0~90 정수) — 판매가는 서버가 10원 단위 반올림으로 계산", example = "15")
        @NotNull(message = "할인율을 입력해 주세요.")
        @Min(value = 0, message = "할인율은 0~90 사이여야 합니다.") @Max(value = 90, message = "할인율은 0~90 사이여야 합니다.")
        Integer discountRate,
        @Schema(description = "상세 설명 HTML — 서버가 script · on* 등 위험한 태그 · 속성을 지우고 저장", example = "<p>…</p>")
        @Size(max = 10000, message = "상세 설명은 10,000자 이하로 입력해 주세요.")
        String description,
        @Schema(description = "소재 · 원재료", example = "리넨 100%")
        @NotBlank(message = "소재를 입력해 주세요.") @Size(max = 100, message = "소재는 100자 이하로 입력해 주세요.")
        String material,
        @Schema(description = "제조국 · 원산지", example = "대한민국")
        @NotBlank(message = "원산지를 입력해 주세요.") @Size(max = 50, message = "원산지는 50자 이하로 입력해 주세요.")
        String origin,
        @Schema(description = "옵션 그룹 1 이름 — 옵션 없는 상품은 생략", example = "컬러")
        String option1Name,
        @Schema(description = "옵션 그룹 2 이름 — 그룹이 1개면 생략", example = "사이즈")
        String option2Name,
        @Schema(description = "옵션 조합 1~100개. 옵션이 없으면 값이 null 인 1개만")
        List<OptionItem> options,
        @Schema(description = "presign 으로 받은 내 스토어 키(products/{storeId}/…) 1~5개 — 첫 번째가 대표", example = "[\"products/1/7f3c9e2a.jpg\"]")
        List<String> imageKeys,
        @Schema(description = "등록 때만 ON_SALE(기본) · STOPPED — 수정 때는 무시", example = "ON_SALE")
        ProductStatus status) {

    public record OptionItem(
            @Schema(description = "등록 때는 null (수정 때 기존 조합 ID)", example = "null")
            Long productOptionId,
            @Schema(description = "옵션 그룹 1 값", example = "오트밀")
            String option1Value,
            @Schema(description = "옵션 그룹 2 값", example = "M")
            String option2Value,
            @Schema(description = "추가금 (원, 0 이상)", example = "0")
            Long extraPrice,
            @Schema(description = "시작 재고 (0 이상)", example = "30")
            Integer stock) {
    }
}
