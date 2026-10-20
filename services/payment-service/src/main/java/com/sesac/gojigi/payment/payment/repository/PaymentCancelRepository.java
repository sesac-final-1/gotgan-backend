package com.sesac.gojigi.payment.payment.repository;

import com.sesac.gojigi.payment.payment.entity.CancelStatus;
import com.sesac.gojigi.payment.payment.entity.PaymentCancel;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentCancelRepository extends JpaRepository<PaymentCancel, Long> {

    Optional<PaymentCancel> findBySagaId(String sagaId);

    Optional<PaymentCancel> findFirstByPaymentIdAndStatus(Long paymentId, CancelStatus status);

    List<PaymentCancel> findByPaymentIdAndStatus(Long paymentId, CancelStatus status);

    /** 복구 스케줄러 — 일정 시간 확정되지 않은 PENDING, 오래된 것부터 */
    List<PaymentCancel> findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(CancelStatus status, LocalDateTime before,
            Limit limit);

    /** 복구 스케줄러 — 포트원이 거절한 자동 환불(saga 없음). 다시 요청할 주체가 없어 스케줄러가 횟수 제한을 두고 재시도 */
    List<PaymentCancel> findByStatusAndSagaIdIsNullAndAttemptCountLessThanAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            CancelStatus status, int maxAttempts, LocalDateTime before, Limit limit);

    /** 이미 우리 기록에 붙은 포트원 취소 내역 id — 대조 때 남의 취소를 내 것으로 오인하지 않게 */
    @Query("select c.portoneCancellationId from PaymentCancel c "
            + "where c.payment.id = :paymentId and c.portoneCancellationId is not null and c.id <> :exceptId")
    List<String> findRecordedCancellationIds(@Param("paymentId") Long paymentId, @Param("exceptId") Long exceptId);
}
