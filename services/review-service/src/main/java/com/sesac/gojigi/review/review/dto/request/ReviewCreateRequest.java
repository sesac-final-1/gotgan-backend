package com.sesac.gojigi.review.review.dto.request;

import com.sesac.gojigi.review.review.entity.ReviewFit;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 리뷰 작성 (review.md 1절) — 프론트 MyReviewModal: 별점 · 사이즈 체감(패션 · 신발가방) · 본문 20자 이상 · 사진 0~3장 */
public record ReviewCreateRequest(
        @Schema(description = "리뷰를 쓸 상품주문 ID (마이페이지 작성 가능 목록)", example = "11")
        @NotNull(message = "상품주문을 선택해 주세요.")
        Long orderItemId,

        @Schema(description = "별점 1~5", example = "5")
        @NotNull(message = "별점을 선택해 주세요.")
        @Min(value = 1, message = "별점은 1~5 사이여야 합니다.") @Max(value = 5, message = "별점은 1~5 사이여야 합니다.")
        Integer rating,

        @Schema(description = "한줄 요약 (선택)", example = "핏이 진짜 예뻐요")
        @Size(max = 100, message = "한줄 요약은 100자 이하로 입력해 주세요.")
        String title,

        @Schema(description = "본문 — 일반 텍스트, 태그는 지우고 저장", example = "평소 M 입는데 M 주문했고 어깨가 살짝 내려오는 오버핏이에요.")
        @NotBlank(message = "리뷰 내용을 입력해 주세요.")
        @Size(min = 20, max = 2000, message = "리뷰 내용은 20~2,000자로 입력해 주세요.")
        String content,

        @Schema(description = "사이즈 체감 — 패션 · 신발가방만, 그 외 생략", example = "TRUE_TO_SIZE")
        ReviewFit fit,

        @Schema(description = "사진 — 업로드 주소 발급(12절)으로 받은 내 키 0~3개, 순서대로 표시", example = "[\"reviews/4/7f3c9e2a-1b2c-4d5e-8f90-a1b2c3d4e5f6.jpg\"]")
        List<String> imageKeys) {
}
