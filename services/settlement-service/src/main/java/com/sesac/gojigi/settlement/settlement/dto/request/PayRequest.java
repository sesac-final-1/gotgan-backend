package com.sesac.gojigi.settlement.settlement.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 지급 실행 — 단건이면 ids 하나 (API 명세 {ids[]}) */
public record PayRequest(@NotEmpty @Size(max = 500) List<Long> ids) {
}
