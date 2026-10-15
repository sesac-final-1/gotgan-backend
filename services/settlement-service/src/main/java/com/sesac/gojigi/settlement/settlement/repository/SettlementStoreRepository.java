package com.sesac.gojigi.settlement.settlement.repository;

import com.sesac.gojigi.settlement.settlement.entity.SettlementStore;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementStoreRepository extends JpaRepository<SettlementStore, Long> {

    Optional<SettlementStore> findByStoreId(Long storeId);

    boolean existsByStoreId(Long storeId);

    /**
     * 이벤트 순서 규칙(docs/events.md, 10/15 A안) — 마지막으로 반영한 occurredAt 보다 새 이벤트만 반영한다.
     * SQS 표준 큐는 순서를 보장하지 않으므로, 늦게 도착한 옛 이벤트는 조건에 걸려 0행 갱신(무시)된다.
     * 비교 · 갱신을 UPDATE 한 문장에서 해서 동시 처리(레플리카 2개)에도 안전하다.
     *
     * @return 갱신된 행 수 — 0 이면 옛 이벤트(또는 스토어 없음)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update SettlementStore s
               set s.storeStatus = :status, s.storeStatusChangedAt = :occurredAt
             where s.storeId = :storeId
               and (s.storeStatusChangedAt is null or s.storeStatusChangedAt < :occurredAt)
            """)
    int applyStatusIfNewer(@Param("storeId") Long storeId, @Param("status") String status,
                           @Param("occurredAt") LocalDateTime occurredAt);
}
