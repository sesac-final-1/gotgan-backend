package com.sesac.gojigi.payment.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 취소 선기록 상태 전이 — PENDING 외 상태에서 확정하면 조용히 넘기지 않고 예외 (잘못된 흐름을 드러냄) */
class PaymentCancelTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 10, 0);

    private static PaymentCancel pending() {
        return PaymentCancel.pending(Payment.ready(1L, "20261022-000001", 64_400L), 10L, "saga-1", 20_000L, 64_400L, "구매자 취소");
    }

    @Test
    @DisplayName("선기록은 PENDING · 직전 잔액 보관, 확정 전에는 포트원 취소 id · 완료 시각이 없다")
    void pending_initialState() {
        PaymentCancel cancel = pending();

        assertThat(cancel.isPending()).isTrue();
        assertThat(cancel.getCancellableBefore()).isEqualTo(64_400L);
        assertThat(cancel.getPortoneCancellationId()).isNull();
        assertThat(cancel.getCancelledAt()).isNull();
    }

    @Test
    @DisplayName("REQUESTED 는 PENDING 유지 + 취소 내역 id 기억, 이후 SUCCEEDED 로 확정하면 사유가 지워진다")
    void requestedThenSucceed() {
        PaymentCancel cancel = pending();

        cancel.markRequested("c-1");
        assertThat(cancel.isPending()).isTrue();
        assertThat(cancel.getPortoneCancellationId()).isEqualTo("c-1");
        assertThat(cancel.getLastError()).contains("REQUESTED");

        cancel.succeed("c-1", NOW);
        assertThat(cancel.isSucceeded()).isTrue();
        assertThat(cancel.getCancelledAt()).isEqualTo(NOW);
        assertThat(cancel.getLastError()).isNull();
    }

    @Test
    @DisplayName("확정된 취소를 다시 확정 · 실패 · 미확정 처리하면 예외 — 이중 반영을 막는다")
    void finalizedCancel_rejectsTransitions() {
        PaymentCancel cancel = pending();
        cancel.succeed("c-1", NOW);

        assertThatThrownBy(() -> cancel.succeed("c-2", NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cancel.fail("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cancel.keepPending("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cancel.markRequested("c-2")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cancel.retry(1L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cancel.recordFailedAttempt("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("FAILED 는 최신 잔액으로 PENDING 부터 재시도 — 이전 포트원 취소 id 는 지운다")
    void failedThenRetry() {
        PaymentCancel cancel = pending();
        cancel.markRequested("c-old");
        cancel.fail("포트원 거절 INVALID_REQUEST");

        cancel.retry(44_400L);

        assertThat(cancel.isPending()).isTrue();
        assertThat(cancel.getCancellableBefore()).isEqualTo(44_400L);
        assertThat(cancel.getPortoneCancellationId()).isNull();
    }

    @Test
    @DisplayName("재시도도 못 한 FAILED 는 시도 횟수만 오른다 — 복구 스케줄러 한도에 닿게")
    void recordFailedAttempt_countsUp() {
        PaymentCancel cancel = pending();
        cancel.fail("x");

        cancel.recordFailedAttempt("포트원 잔액 없음");
        cancel.countAttempt();

        assertThat(cancel.getAttemptCount()).isEqualTo(2);
        assertThat(cancel.getLastError()).isEqualTo("포트원 잔액 없음");
    }

    @Test
    @DisplayName("사유는 컬럼 길이(200자)에 맞게 자른다 — 긴 포트원 메시지로 저장이 실패하지 않게")
    void longError_truncated() {
        PaymentCancel cancel = pending();

        cancel.keepPending("가".repeat(300));

        assertThat(cancel.getLastError()).hasSize(200);
    }
}
