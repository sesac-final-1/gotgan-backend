package com.sesac.gojigi.order.claim.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.order.claim.exception.ClaimErrorCode;
import com.sesac.gojigi.order.claim.exception.ClaimException;
import com.sesac.gojigi.order.order.entity.OrderFixtures;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 클레임 상태 전이 — REQUESTED → APPROVED → COMPLETED, 처리 시각 기록 */
class OrderClaimTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 12, 0);

    private static OrderClaim claim() {
        return OrderClaim.builder().orderItem(OrderFixtures.item(OrderFixtures.order())).requesterId(4L)
                .claimType(ClaimType.CANCEL).reasonCode(ClaimReasonCode.ETC).refundAmt(8_000L).restorePoint(2_000L)
                .requestedAt(NOW).build();
    }

    @Test
    @DisplayName("승인 → 완료, 처리 시각은 마지막 처리 시각")
    void approveThenComplete() {
        // given
        OrderClaim claim = claim();

        // when
        claim.approve(NOW);
        claim.complete(NOW.plusMinutes(1));

        // then
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.COMPLETED);
        assertThat(claim.getProcessedAt()).isEqualTo(NOW.plusMinutes(1));
        assertThat(claim.getRestorePoint()).isEqualTo(2_000L);
    }

    @Test
    @DisplayName("승인 전 완료 · 두 번 승인은 409 CLAIM_INVALID_STATUS")
    void invalidTransition() {
        OrderClaim claim = claim();

        assertThatThrownBy(() -> claim.complete(NOW)).isInstanceOf(ClaimException.class)
                .extracting(e -> ((ClaimException) e).getErrorCode()).isEqualTo(ClaimErrorCode.CLAIM_INVALID_STATUS);
        claim.approve(NOW);
        assertThatThrownBy(() -> claim.approve(NOW)).isInstanceOf(ClaimException.class);
    }
}
