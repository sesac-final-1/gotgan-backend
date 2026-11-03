package com.sesac.gojigi.order.common.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxEventTest {

    private static OutboxEvent row() {
        return OutboxEvent.of("e-1", OutboxEvent.AggregateType.ORDER, "O-1", "OrderPaid", "{}");
    }

    @Test
    @DisplayName("실패 기록 — 한도 전까지는 NEW 로 남아 발행기가 다시, 한도째 실패하면 FAILED")
    void markFailed_untilMaxAttempts() {
        // given
        OutboxEvent row = row();

        // when · then
        for (int i = 1; i < 10; i++) {
            row.markFailed("boom", 10);
            assertThat(row.getStatus()).isEqualTo(OutboxEvent.Status.NEW);
        }
        row.markFailed("boom", 10);
        assertThat(row.getStatus()).isEqualTo(OutboxEvent.Status.FAILED);
        assertThat(row.getAttemptCount()).isEqualTo(10);
        assertThat(row.isSent()).isFalse();
    }

    @Test
    @DisplayName("실패 사유는 200자로 자른다")
    void markFailed_truncatesError() {
        // given
        OutboxEvent row = row();

        // when
        row.markFailed("x".repeat(300), 10);

        // then
        assertThat(row.getLastError()).hasSize(200);
    }
}
