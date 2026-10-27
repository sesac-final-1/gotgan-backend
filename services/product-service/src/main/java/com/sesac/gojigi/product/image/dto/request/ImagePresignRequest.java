package com.sesac.gojigi.product.image.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 상품 이미지 업로드 주소 발급 (product.md 17절) — store 입점 서류 presign 과 같은 모양(docType 없음).
 * 개수(1~5) · 형식 · 크기 위반은 서비스가 PRODUCT_IMAGE_INVALID 로 검사한다.
 */
public record ImagePresignRequest(
        @Schema(description = "올릴 이미지 목록 (1~5개) — 응답은 같은 순서")
        @NotNull(message = "올릴 이미지를 선택해 주세요.")
        List<@Valid @NotNull FileItem> files) {

    public record FileItem(
            @Schema(description = "이미지 형식 — 업로드 PUT 의 Content-Type 도 이 값", example = "image/jpeg")
            @NotBlank(message = "이미지 형식이 없습니다.")
            String contentType,

            @Schema(description = "파일 크기(바이트) — 서명에 들어가 다른 크기로는 업로드되지 않음", example = "834512")
            @NotNull(message = "파일 크기가 없습니다.")
            Long size) {
    }
}
