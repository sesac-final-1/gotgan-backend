package com.sesac.gojigi.review.eligibility.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReviewEligibilityTest {

    private static final LocalDateTime CONFIRMED_AT = LocalDateTime.of(2026, 10, 20, 14, 0);

    private static ReviewEligibility eligibility() {
        return ReviewEligibility.builder()
                .orderItemId(11L).userId(4L).productId(101L).storeId(1L)
                .buyerDisplayName("홍*동").productName("린넨 오버핏 셔츠").confirmedAt(CONFIRMED_AT)
                .build();
    }

    @Test
    @DisplayName("작성 기한은 구매확정 시각 + 30일로 정해진다")
    void builder_setsExpiresAtThirtyDaysAfterConfirm() {
        // when
        ReviewEligibility eligibility = eligibility();

        // then
        assertThat(eligibility.getExpiresAt()).isEqualTo(CONFIRMED_AT.plusDays(30));
        assertThat(eligibility.getBuyerDisplayName()).isEqualTo("홍*동");
        assertThat(eligibility.getProductName()).isEqualTo("린넨 오버핏 셔츠");
    }

    @Test
    @DisplayName("기한 시각까지는 만료가 아니고, 지나면 만료다")
    void isExpired_trueOnlyAfterExpiresAt() {
        // given
        ReviewEligibility eligibility = eligibility();
        LocalDateTime expiresAt = eligibility.getExpiresAt();

        // when · then
        assertThat(eligibility.isExpired(expiresAt.minusSeconds(1))).isFalse();
        assertThat(eligibility.isExpired(expiresAt)).isFalse();
        assertThat(eligibility.isExpired(expiresAt.plusNanos(1000))).isTrue();
    }

    @Test
    @DisplayName("구매자 본인만 true — 다른 회원의 자격으로 작성 차단")
    void isOwnedBy_trueOnlyForBuyer() {
        // given
        ReviewEligibility eligibility = eligibility();

        // when · then
        assertThat(eligibility.isOwnedBy(4L)).isTrue();
        assertThat(eligibility.isOwnedBy(5L)).isFalse();
        assertThat(eligibility.isOwnedBy(null)).isFalse();
    }
}
