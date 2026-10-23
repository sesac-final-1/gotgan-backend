package com.sesac.gojigi.order.saga.repository;

import com.sesac.gojigi.order.saga.entity.SagaInstance;
import com.sesac.gojigi.order.saga.entity.SagaStatus;
import com.sesac.gojigi.order.saga.entity.SagaStep;
import com.sesac.gojigi.order.saga.entity.SagaType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SagaInstanceRepository extends JpaRepository<SagaInstance, Long> {

    /** 주문의 주문 생성 Saga — 주문 1건에 1개 */
    Optional<SagaInstance> findByOrderIdAndSagaType(Long orderId, SagaType sagaType);

    /**
     * 진행 중(STARTED)인데 steps 단계에서 cutoff 이전부터 멈춘 Saga — 주문을 함께 읽는다 (스케줄러는 트랜잭션 밖에서 쓴다).
     * idx_saga_instance_status_updated (status, updated_at)
     */
    @Query("""
            select s from SagaInstance s join fetch s.order
            where s.sagaType = :type and s.status = :status and s.currentStep in :steps and s.updatedAt < :cutoff
            order by s.id
            """)
    List<SagaInstance> findStalled(@Param("type") SagaType type, @Param("status") SagaStatus status,
                                   @Param("steps") Collection<SagaStep> steps, @Param("cutoff") LocalDateTime cutoff,
                                   Pageable page);

    /**
     * 다시 시도할 Saga — 재시도 시각이 지났거나(호출 실패), 재시도 시각 없이 cutoff 이전부터 멈춘(도중 파드 종료) Saga.
     * 주문 Saga 는 COMPENSATING(보상 재시도), 취소 Saga 는 STARTED(앞으로 진행 재시도).
     * idx_saga_instance_status_next_retry (status, next_retry_at)
     */
    @Query("""
            select s from SagaInstance s join fetch s.order
            where s.sagaType = :type and s.status = :status
              and (s.nextRetryAt <= :now or (s.nextRetryAt is null and s.updatedAt < :cutoff))
            order by s.id
            """)
    List<SagaInstance> findRetryDue(@Param("type") SagaType type, @Param("status") SagaStatus status,
                                           @Param("now") LocalDateTime now, @Param("cutoff") LocalDateTime cutoff,
                                           Pageable page);
}
