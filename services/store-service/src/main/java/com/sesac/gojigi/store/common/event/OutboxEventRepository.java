package com.sesac.gojigi.store.common.event;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 발행기 — 커밋 직후 발행이 실패했거나 아직 안 보낸 행, 오래된 것부터 (idx_outbox_event_status).
     * 시도 한도에 닿은 행은 뺀다 — 영구 실패 행이 앞 50건을 막아 뒤의 새 이벤트가 안 나가는 것 방지
     */
    List<OutboxEvent> findByStatusAndAttemptCountLessThanAndOccurredAtBeforeOrderByIdAsc(OutboxEvent.Status status,
            int maxAttempts, LocalDateTime before, Limit limit);
}
