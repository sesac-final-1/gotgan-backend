package com.sesac.gojigi.order.claim.service;

import com.sesac.gojigi.order.claim.entity.ClaimReasonCode;
import java.util.Set;

/**
 * 클레임 환불 금액 (스펙 05장) — 현금(refundAmt)은 그 상품주문의 실결제 배분액(상품주문 금액 - 사용 포인트) 기준, 사용 포인트는 포인트로 돌려준다.
 * 현금은 주문에 남은 결제 금액(cashLeft)을 넘지 않는다 — 넘는 몫은 포인트로.
 */
public final class ClaimRefundPolicy {

    /** 판매자 귀책 — 반품 배송비를 받지 않는다 (스펙: 불량 · 오배송은 전액) */
    private static final Set<ClaimReasonCode> SELLER_FAULT = Set.of(ClaimReasonCode.DEFECTIVE,
            ClaimReasonCode.WRONG_DELIVERY);
    /** 반품 배송비 = 스토어 기본 배송비 × 2 (왕복) */
    private static final int RETURN_FEE_TIMES = 2;

    private ClaimRefundPolicy() {
    }

    /**
     * shippingFee: 취소는 돌려주는 배송비(+, 스토어 마지막 상품주문일 때), 반품은 빼는 반품 배송비(-) — 화면 표시용.
     * restorePoint: 돌려줄 사용 포인트 + 현금으로 못 돌려준 몫.
     */
    public record Refund(long refundAmt, long restorePoint, long shippingFee) {
    }

    public static boolean sellerFault(ClaimReasonCode reasonCode) {
        return SELLER_FAULT.contains(reasonCode);
    }

    /** 반품 배송비 — 판매자 귀책이면 0 */
    public static long returnShippingFee(ClaimReasonCode reasonCode, long storeBaseFee) {
        return sellerFault(reasonCode) ? 0L : storeBaseFee * RETURN_FEE_TIMES;
    }

    /** 취소 — 스토어 마지막 상품주문이면 그 스토어 배송비도 돌려준다 (배송하지 않으므로) */
    public static Refund cancel(long itemAmt, long usedPoint, long storeShippingFeeIfLast, long cashLeft) {
        long due = itemAmt - usedPoint + storeShippingFeeIfLast;
        long refundAmt = Math.min(due, cashLeft);
        return new Refund(refundAmt, usedPoint + (due - refundAmt), storeShippingFeeIfLast);
    }

    /** 반품 — 실결제 배분액에서 반품 배송비를 뺀다(0 아래로는 안 내려감). 사용 포인트는 그대로 돌려준다 */
    public static Refund ret(long itemAmt, long usedPoint, long returnShippingFee, long cashLeft) {
        long due = Math.max(0L, itemAmt - usedPoint - returnShippingFee);
        long refundAmt = Math.min(due, cashLeft);
        return new Refund(refundAmt, usedPoint + (due - refundAmt), -returnShippingFee);
    }
}
