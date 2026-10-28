package com.sesac.gojigi.store.store.dto.request;

import com.sesac.gojigi.store.store.entity.StoreStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 관리자 스토어 정지 · 해제 요청 (docs/api/store.md 9절) — CLOSED 는 서비스가 400 (폐점은 판매자 API) */
public record StoreStatusChangeRequest(
        @Schema(description = "바꿀 상태 — SUSPENDED(정지) · ACTIVE(해제)만", example = "SUSPENDED", allowableValues = {"SUSPENDED", "ACTIVE"})
        @NotNull(message = "바꿀 상태를 선택해 주세요.")
        StoreStatus status,

        @Schema(description = "정지 · 해제 사유 (필수, 255자 이하)", example = "허위 광고 신고 3건 확인")
        @NotBlank(message = "사유를 입력해 주세요.")
        @Size(max = 255, message = "사유는 255자 이하로 입력해 주세요.")
        String reason) {
}
