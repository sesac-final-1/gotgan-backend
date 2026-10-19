package com.sesac.gojigi.settlement.commission.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 카테고리 수수료 — 오늘 적용 중인 율 + 예약분(적용 시작일이 미래) */
public record CommissionResponse(Long categoryId, Rate current, List<Rate> reserved) {

    public record Rate(BigDecimal rate, LocalDate effectiveFrom) {
    }
}
