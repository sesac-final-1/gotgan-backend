package com.sesac.gojigi.settlement.settlement.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 정산 수수료 계산 (스펙 05장 정산 규칙) — 상품주문 금액 기준, 원 단위 내림.
 * 판매수수료 = 금액 × 카테고리 수수료율(%), 결제수수료 = 금액 × 2.2% (판매수수료와 별도). 실수(double) 대신 BigDecimal.
 */
public final class FeeCalculator {

    /** 결제수수료율 2.2% — 포트원 · PG 수수료를 판매자가 부담 (스펙 05장) */
    public static final BigDecimal PAYMENT_FEE_RATE = new BigDecimal("2.2");

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private FeeCalculator() {
    }

    /** 금액 × 율(%) — 원 단위 내림 */
    public static long percentOf(long amount, BigDecimal ratePercent) {
        return BigDecimal.valueOf(amount).multiply(ratePercent).divide(HUNDRED, 0, RoundingMode.FLOOR).longValueExact();
    }

    public static long commission(long itemAmt, BigDecimal commissionRatePercent) {
        return percentOf(itemAmt, commissionRatePercent);
    }

    public static long paymentFee(long itemAmt) {
        return percentOf(itemAmt, PAYMENT_FEE_RATE);
    }
}
