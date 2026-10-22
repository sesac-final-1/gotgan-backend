package com.sesac.gojigi.order.saga.repository;

import com.sesac.gojigi.order.saga.entity.SagaInstance;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SagaInstanceRepository extends JpaRepository<SagaInstance, Long> {
}
