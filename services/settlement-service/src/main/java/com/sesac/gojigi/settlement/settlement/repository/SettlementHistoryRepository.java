package com.sesac.gojigi.settlement.settlement.repository;

import com.sesac.gojigi.settlement.settlement.entity.SettlementHistory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementHistoryRepository extends JpaRepository<SettlementHistory, Long> {

    List<SettlementHistory> findBySettlementIdOrderByIdAsc(Long settlementId);

    /** 가장 최근 이력 — 보류를 건 주체(SYSTEM · ADMIN) 판단 */
    Optional<SettlementHistory> findFirstBySettlementIdOrderByIdDesc(Long settlementId);
}
