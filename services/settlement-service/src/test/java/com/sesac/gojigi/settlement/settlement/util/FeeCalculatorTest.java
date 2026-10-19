package com.sesac.gojigi.settlement.settlement.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 정산 수수료 계산 — 스펙 05장 "계산 단위 테스트 필수" (STL-04) */
class FeeCalculatorTest {

    @ParameterizedTest(name = "{0}원 × {1}% = {2}원")
    @CsvSource({
            "59000, 11.00, 6490",      // 나누어떨어짐
            "12345, 11.00, 1357",      // 1357.95 → 내림
            "12345, 11.50, 1419",      // 소수 율 1419.675 → 내림
            "100, 7.00, 7",
            "99, 7.00, 6",             // 6.93 → 내림
            "0, 11.00, 0"
    })
    @DisplayName("판매수수료 = 금액 × 수수료율(%), 원 단위 내림")
    void commission_floorsToWon(long amount, BigDecimal rate, long expected) {
        assertThat(FeeCalculator.commission(amount, rate)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}원 × 2.2% = {1}원")
    @CsvSource({
            "59000, 1298",
            "12345, 271",              // 271.59 → 내림
            "45, 0",                   // 0.99 → 내림
            "0, 0"
    })
    @DisplayName("결제수수료 = 금액 × 2.2%, 원 단위 내림 (판매수수료와 별도)")
    void paymentFee_floorsToWon(long amount, long expected) {
        assertThat(FeeCalculator.paymentFee(amount)).isEqualTo(expected);
    }

    @Test
    @DisplayName("지급액 = 매출 − 판매수수료 − 결제수수료 — 패션의류 11% 기준 59,000원 → 51,212원")
    void payout_example() {
        long itemAmt = 59_000L;
        long payout = itemAmt - FeeCalculator.commission(itemAmt, new BigDecimal("11.00")) - FeeCalculator.paymentFee(itemAmt);

        assertThat(payout).isEqualTo(51_212L);
    }
}
