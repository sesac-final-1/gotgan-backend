package com.sesac.gojigi.review.review.dto.request;

import com.sesac.gojigi.review.review.entity.ReviewFit;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 내 리뷰 수정 (review.md 2절) — 작성과 같은 규칙, 통째로 바꾼다(요약 · 사이즈 체감을 비우면 지워짐) */
public record ReviewUpdateRequest(
        @Schema(description = "별점 1~5", example = "4")
        @NotNull(message = "별점을 선택해 주세요.")
        @Min(value = 1, message = "별점은 1~5 사이여야 합니다.") @Max(value = 5, message = "별점은 1~5 사이여야 합니다.")
        Integer rating,

        @Schema(description = "한줄 요약 (선택)", example = "길이가 조금 길어요")
        @Size(max = 100, message = "한줄 요약은 100자 이하로 입력해 주세요.")
        String title,

        @Schema(description = "본문 — 일반 텍스트, 태그는 지우고 저장", example = "키 160인데 기장이 살짝 길어서 수선했어요. 그 외에는 만족합니다.")
        @NotBlank(message = "리뷰 내용을 입력해 주세요.")
        @Size(min = 20, max = 2000, message = "리뷰 내용은 20~2,000자로 입력해 주세요.")
        String content,

        @Schema(description = "사이즈 체감 — 패션 · 신발가방만, 그 외 생략", example = "LARGE")
        ReviewFit fit,

        @Schema(description = "사진 0~3개 — 통째로 바꾼다(비우면 사진 삭제). 기존 사진은 그 키를 그대로, 새 사진은 업로드 주소 발급 키",
                example = "[\"reviews/4/7f3c9e2a-1b2c-4d5e-8f90-a1b2c3d4e5f6.jpg\"]")
        List<String> imageKeys) {
}
