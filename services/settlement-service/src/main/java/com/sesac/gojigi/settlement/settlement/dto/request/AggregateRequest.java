package com.sesac.gojigi.settlement.settlement.dto.request;

import jakarta.validation.constraints.Pattern;

/** 지금 집계 — week 를 비우면 지난주 (ISO 주차 2026-W43) */
public record AggregateRequest(@Pattern(regexp = "\\d{4}-W\\d{2}", message = "주차 형식은 2026-W43 입니다.") String week) {
}
