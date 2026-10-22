package com.sesac.gojigi.order.saga.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.order.order.entity.OrderFixtures;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Saga 진행 · 보상 · 재시도 기록 (V3 attempt_count · next_retry_at) */
class SagaInstanceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 12, 0);

    private final SagaInstance saga = SagaInstance.placeOrder(OrderFixtures.order(), "saga-1");

    @Test
    @DisplayName("단계를 마치면 재시도 기록을 지우고, 완료는 DONE · COMPLETED")
    void advance_resetsRetry_andComplete() {
        // given
        saga.scheduleRetry("timeout", NOW);

        // when
        saga.advance(SagaStep.POINT_USED);

        // then
        assertThat(saga.getCurrentStep()).isEqualTo(SagaStep.POINT_USED);
        assertThat(saga.getAttemptCount()).isZero();
        assertThat(saga.getNextRetryAt()).isNull();

        saga.complete();
        assertThat(saga.getCurrentStep()).isEqualTo(SagaStep.DONE);
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPLETED);
    }

    @Test
    @DisplayName("보상 시작은 원인을 남기고(500자까지), 보상 완료는 COMPENSATED")
    void compensating_thenCompensated() {
        // when
        saga.startCompensating("x".repeat(600));

        // then
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPENSATING);
        assertThat(saga.getLastError()).hasSize(500);

        saga.compensated();
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(saga.getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("재시도는 1 · 1 · 2분 후 5분 간격으로 8회, 그다음 실패는 FAILED (사람이 확인)")
    void scheduleRetry_backoffThenFailed() {
        // when · then
        long[] minutes = { 1, 1, 2, 5, 5, 5, 5, 5 };
        for (int i = 0; i < minutes.length; i++) {
            saga.scheduleRetry("PAYMENT_CANCEL_PENDING", NOW);
            assertThat(saga.getNextRetryAt()).isEqualTo(NOW.plusMinutes(minutes[i]));
            assertThat(saga.getAttemptCount()).isEqualTo(i + 1);
        }
        saga.scheduleRetry(null, NOW);
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.FAILED);
        assertThat(saga.getNextRetryAt()).isNull();
        assertThat(saga.getLastError()).isNull();
    }
}
