package com.sesac.gojigi.review.common.event;

import org.springframework.data.jpa.repository.JpaRepository;

/** 이벤트 소비 멱등 — 처리 전 존재 확인, 처리와 같은 트랜잭션에서 저장 (uk_processed_event_event_id 가 최종 방어선) */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    boolean existsByEventId(String eventId);
}
