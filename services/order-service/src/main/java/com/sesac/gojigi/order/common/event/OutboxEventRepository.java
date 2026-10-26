package com.sesac.gojigi.order.common.event;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /** 발행기 — 커밋 직후 발행이 실패했거나 아직 안 보낸 행, 오래된 것부터 (idx_outbox_event_status) */
    List<OutboxEvent> findByStatusAndCreatedAtBeforeOrderByIdAsc(OutboxEvent.Status status, LocalDateTime before,
                                                                 Limit limit);
}
