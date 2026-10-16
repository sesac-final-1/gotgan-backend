package com.sesac.gojigi.payment.payment.repository;

import com.sesac.gojigi.payment.payment.entity.PaymentCancel;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentCancelRepository extends JpaRepository<PaymentCancel, Long> {

    Optional<PaymentCancel> findBySagaId(String sagaId);
}
