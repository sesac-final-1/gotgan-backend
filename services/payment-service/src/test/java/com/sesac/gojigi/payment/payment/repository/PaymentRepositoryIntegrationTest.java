package com.sesac.gojigi.payment.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.payment.TestcontainersConfiguration;
import com.sesac.gojigi.payment.payment.entity.Payment;
import com.sesac.gojigi.payment.payment.entity.PaymentCancel;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class PaymentRepositoryIntegrationTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentCancelRepository paymentCancelRepository;

    @Test
    @DisplayName("결제는 orderNo · 포트원 paymentId 로 찾고, 같은 orderNo 두 번째 저장은 유니크 제약이 막는다")
    void findByOrderNo_andUniqueOrderNo() {
        // given
        paymentRepository.saveAndFlush(Payment.ready(1L, "20261021-000123", 64_400L));

        // when · then
        assertThat(paymentRepository.findByOrderNo("20261021-000123")).isPresent();
        assertThat(paymentRepository.findByPortonePaymentId("pay-20261021-000123")).isPresent();
        assertThatThrownBy(() -> paymentRepository.saveAndFlush(Payment.ready(2L, "20261021-000123", 1_000L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 sagaId 취소 기록은 하나만 — NULL(자동 취소)은 여러 건 허용")
    void paymentCancel_sagaIdUnique_nullAllowed() {
        // given
        Payment payment = paymentRepository.saveAndFlush(Payment.ready(1L, "20261021-000123", 64_400L));
        LocalDateTime now = LocalDateTime.now();
        paymentCancelRepository.saveAndFlush(PaymentCancel.of(payment, 10L, "saga-1", 1_000L, "구매자 취소", "c-1", now));
        paymentCancelRepository.saveAndFlush(PaymentCancel.of(payment, null, null, 1_000L, "자동 취소", "c-2", now));
        paymentCancelRepository.saveAndFlush(PaymentCancel.of(payment, null, null, 1_000L, "자동 취소", "c-3", now));

        // when · then
        assertThat(paymentCancelRepository.findBySagaId("saga-1")).isPresent();
        assertThatThrownBy(() -> paymentCancelRepository.saveAndFlush(
                PaymentCancel.of(payment, 10L, "saga-1", 1_000L, "구매자 취소", "c-4", now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
