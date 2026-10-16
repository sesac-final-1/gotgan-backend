package com.sesac.gojigi.payment.payment.repository;

import com.sesac.gojigi.payment.payment.entity.Payment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderNo(String orderNo);

    Optional<Payment> findByPortonePaymentId(String portonePaymentId);
}
