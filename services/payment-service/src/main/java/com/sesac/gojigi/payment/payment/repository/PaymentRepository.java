package com.sesac.gojigi.payment.payment.repository;

import com.sesac.gojigi.payment.payment.entity.Payment;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderNo(String orderNo);

    Optional<Payment> findByPortonePaymentId(String portonePaymentId);

    /** 취소 선기록 직전 — 같은 결제의 취소 시작을 한 줄로 세운다 (한 결제에 PENDING 1건만). 포트원 호출 전 짧은 트랜잭션에서만 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);
}
