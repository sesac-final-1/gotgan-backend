package com.sesac.gojigi.order.claim.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.order.claim.entity.ClaimReasonCode;
import com.sesac.gojigi.order.claim.service.ClaimRefundPolicy.Refund;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 클레임 환불 금액 — 취소(스토어 마지막 배송비) · 반품(사유별 반품 배송비) · 남은 결제 금액 상한 */
class ClaimRefundPolicyTest {

    @Test
    @DisplayName("취소 — 상품주문 금액 - 사용 포인트 + 스토어 마지막이면 배송비, 남은 결제 금액을 넘는 몫은 포인트로")
    void cancel() {
        assertThat(ClaimRefundPolicy.cancel(10_000L, 2_000L, 0L, 100_000L)).isEqualTo(new Refund(8_000L, 2_000L, 0L));
        assertThat(ClaimRefundPolicy.cancel(5_000L, 0L, 3_000L, 100_000L)).isEqualTo(new Refund(8_000L, 0L, 3_000L));
        assertThat(ClaimRefundPolicy.cancel(5_000L, 5_000L, 3_000L, 0L)).isEqualTo(new Refund(0L, 8_000L, 3_000L));
    }

    @Test
    @DisplayName("반품 — 단순변심은 스토어 기본 배송비 × 2 차감(0 아래로는 안 감), 불량 · 오배송은 차감 없음")
    void ret() {
        long changeOfMind = ClaimRefundPolicy.returnShippingFee(ClaimReasonCode.CHANGE_OF_MIND, 3_000L);
        assertThat(changeOfMind).isEqualTo(6_000L);
        assertThat(ClaimRefundPolicy.returnShippingFee(ClaimReasonCode.DEFECTIVE, 3_000L)).isZero();
        assertThat(ClaimRefundPolicy.returnShippingFee(ClaimReasonCode.WRONG_DELIVERY, 3_000L)).isZero();
        assertThat(ClaimRefundPolicy.returnShippingFee(ClaimReasonCode.DELAYED, 3_000L)).isEqualTo(6_000L);

        assertThat(ClaimRefundPolicy.ret(20_000L, 2_000L, changeOfMind, 100_000L))
                .isEqualTo(new Refund(12_000L, 2_000L, -6_000L));
        assertThat(ClaimRefundPolicy.ret(5_000L, 0L, changeOfMind, 100_000L)).isEqualTo(new Refund(0L, 0L, -6_000L));
        assertThat(ClaimRefundPolicy.ret(20_000L, 0L, 0L, 15_000L)).isEqualTo(new Refund(15_000L, 5_000L, 0L));
    }
}
