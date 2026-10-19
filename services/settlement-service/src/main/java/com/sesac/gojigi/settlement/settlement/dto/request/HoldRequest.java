package com.sesac.gojigi.settlement.settlement.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 지급 보류 · 해제 — hold=true 면 reason 필수 (API 명세 {hold, reason}) */
public record HoldRequest(@NotNull Boolean hold, @Size(max = 255) String reason) {
}
