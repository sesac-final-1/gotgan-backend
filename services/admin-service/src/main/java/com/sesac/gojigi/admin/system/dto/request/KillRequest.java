package com.sesac.gojigi.admin.system.dto.request;

import com.sesac.gojigi.admin.system.entity.KillScope;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 내리기 — scope ONE(Ready 파드 1개) · ALL(전부), reason 선택 */
public record KillRequest(
        @NotNull KillScope scope,
        @Size(max = 255) String reason) {
}
