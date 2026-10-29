package com.sesac.gojigi.store.apply.dto.request;

import com.sesac.gojigi.store.apply.entity.DocType;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 입점 보완요청 (docs/api/store.md 7절) — 기한은 받지 않고 서버가 +7일로 계산. 서류 중복은 서비스가 400 으로 */
public record ApplicationSupplementRequest(
        @Schema(description = "보완 안내 (필수, 255자 이하) — 신청자 화면 · 메일에 그대로 보임", example = "사본이 흐려 확인이 어렵습니다")
        @NotBlank(message = "보완 요청 내용을 입력해 주세요.")
        @Size(max = 255, message = "보완 요청 내용은 255자 이하로 입력해 주세요.")
        String message,

        @ArraySchema(arraySchema = @Schema(description = "다시 낼 서류 0~4개 · 중복 금지 — 서류가 아닌 내용 보완이면 []"),
                schema = @Schema(implementation = DocType.class), maxItems = 4)
        @NotNull(message = "보완 요청 서류를 선택해 주세요. 서류가 아니면 빈 목록으로 보내 주세요.")
        @Size(max = 4, message = "보완 요청 서류는 4개 이하로 선택해 주세요.")
        List<@NotNull(message = "보완 요청 서류 종류가 올바르지 않습니다.") DocType> requestedDocs) {
}
