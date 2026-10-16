package com.sesac.gojigi.payment.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.payment.payment.exception.PaymentErrorCode;
import com.sesac.gojigi.payment.payment.exception.PaymentException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 9, 12, 33);

    private static Payment paid(long amount) {
        Payment payment = Payment.ready(1L, "20261021-000123", amount);
        payment.markPaid("pg-tx-1", "CARD", NOW);
        return payment;
    }

    @Test
    @DisplayName("결제 준비는 READY 이고 포트원 paymentId 는 pay-{orderNo}")
    void ready_createsReadyPaymentWithDerivedPortoneId() {
        Payment payment = Payment.ready(1L, "20261021-000123", 64_400L);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(payment.getPortonePaymentId()).isEqualTo("pay-20261021-000123");
        assertThat(payment.remainingAmount()).isEqualTo(64_400L);
    }

    @Test
    @DisplayName("READY 에서만 PAID 가 되고, 이미 PAID 면 다시 바꿀 수 없다")
    void markPaid_onlyFromReady() {
        Payment payment = paid(64_400L);

        assertThat(payment.isPaid()).isTrue();
        assertThat(payment.getPgTxId()).isEqualTo("pg-tx-1");
        assertThatThrownBy(() -> payment.markPaid("pg-tx-2", "CARD", NOW))
                .isInstanceOf(PaymentException.class)
                .extracting("errorCode").isEqualTo(PaymentErrorCode.PAYMENT_INVALID_STATUS);
    }

    @Test
    @DisplayName("READY 에서만 FAILED 가 된다")
    void markFailed_onlyFromReady() {
        Payment ready = Payment.ready(1L, "20261021-000123", 64_400L);
        ready.markFailed();
        assertThat(ready.getStatus()).isEqualTo(PaymentStatus.FAILED);

        assertThatThrownBy(() -> paid(64_400L).markFailed())
                .extracting("errorCode").isEqualTo(PaymentErrorCode.PAYMENT_INVALID_STATUS);
    }

    @Test
    @DisplayName("부분 취소가 누적되어 결제 금액과 같아지면 CANCELLED · 취소 시각 기록")
    void applyCancel_accumulatesUntilCancelled() {
        Payment payment = paid(64_400L);

        payment.applyCancel(32_200L, NOW);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELLED);
        assertThat(payment.getCancelledAt()).isNull();

        payment.applyCancel(32_200L, NOW);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(payment.getCancelledAmount()).isEqualTo(64_400L);
        assertThat(payment.getCancelledAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("남은 금액보다 큰 취소 · 결제 전 취소는 거절한다")
    void applyCancel_rejectsExceededAmountAndUnpaidPayment() {
        Payment payment = paid(10_000L);
        assertThatThrownBy(() -> payment.applyCancel(10_001L, NOW))
                .extracting("errorCode").isEqualTo(PaymentErrorCode.PAYMENT_CANCEL_AMOUNT_EXCEEDED);

        Payment ready = Payment.ready(1L, "20261021-000124", 10_000L);
        assertThatThrownBy(() -> ready.applyCancel(1_000L, NOW))
                .extracting("errorCode").isEqualTo(PaymentErrorCode.PAYMENT_INVALID_STATUS);
    }
}
