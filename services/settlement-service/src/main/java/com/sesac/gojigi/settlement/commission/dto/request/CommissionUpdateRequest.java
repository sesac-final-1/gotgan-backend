package com.sesac.gojigi.settlement.commission.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 수수료율 1건 변경(예약) — rate 는 % (11.5 = 11.5%), effectiveFrom 을 비우면 다음 주 월요일.
 * 같은 카테고리 · 같은 적용 시작일이 이미 있으면 율만 바꾼다(예약 수정). 지난 날짜는 거절(이미 적재된 정산이 바뀌지 않게).
 */
public record CommissionUpdateRequest(
        @NotNull Long categoryId,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") @Digits(integer = 3, fraction = 2) BigDecimal rate,
        LocalDate effectiveFrom) {
}
