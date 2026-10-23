package com.sesac.gojigi.order.saga.service;

import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.saga.entity.SagaInstance;
import com.sesac.gojigi.order.saga.entity.SagaStep;
import com.sesac.gojigi.order.saga.repository.SagaInstanceRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * saga_instance 기록 — 메서드마다 짧은 트랜잭션 하나 (가이드라인 06장).
 * 참여자를 부르기 전에 sagaId 가 커밋돼 있어야 결과를 모르는 호출도 같은 sagaId 로 다시 부를 수 있다.
 */
@Service
@RequiredArgsConstructor
public class OrderSagaTxService {

    private final SagaInstanceRepository sagaInstanceRepository;
    private final Clock clock;

    @Transactional
    public SagaInstance startPlaceOrder(Order order) {
        return sagaInstanceRepository.save(SagaInstance.placeOrder(order, UUID.randomUUID().toString()));
    }

    @Transactional
    public void advance(Long sagaInstanceId, SagaStep step) {
        get(sagaInstanceId).advance(step);
    }

    @Transactional
    public void complete(Long sagaInstanceId) {
        get(sagaInstanceId).complete();
    }

    @Transactional
    public void startCompensating(Long sagaInstanceId, String reason) {
        get(sagaInstanceId).startCompensating(reason);
    }

    @Transactional
    public void compensated(Long sagaInstanceId) {
        get(sagaInstanceId).compensated();
    }

    /** 보상이 끝나지 않음 — Saga 복구 스케줄러가 nextRetryAt 에 같은 sagaId 로 다시 시도 (한도 넘으면 FAILED) */
    @Transactional
    public void scheduleRetry(Long sagaInstanceId, String reason) {
        get(sagaInstanceId).scheduleRetry(reason, LocalDateTime.now(clock));
    }

    private SagaInstance get(Long sagaInstanceId) {
        return sagaInstanceRepository.findById(sagaInstanceId)
                .orElseThrow(() -> new IllegalStateException("saga_instance 없음 - id=" + sagaInstanceId));
    }
}
