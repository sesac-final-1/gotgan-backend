package com.sesac.gojigi.settlement.settlement.repository;

import com.sesac.gojigi.settlement.settlement.entity.SettlementItem;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementItemRepository extends JpaRepository<SettlementItem, Long> {

    boolean existsByOrderItemId(Long orderItemId);

    Optional<SettlementItem> findByOrderItemId(Long orderItemId);

    List<SettlementItem> findBySettlementIdOrderByConfirmedAtAsc(Long settlementId);

    /** 주간 집계 대상 — 아직 정산서에 연결되지 않은 그 주 구매확정분 [from, until) */
    @Query("""
            select i from SettlementItem i
             where i.settlementId is null and i.confirmedAt >= :from and i.confirmedAt < :until
             order by i.storeId, i.confirmedAt
            """)
    List<SettlementItem> findUnassigned(@Param("from") LocalDateTime from, @Param("until") LocalDateTime until);

    /** 스토어의 집계 전 구매확정분 (판매자 대시보드 "이번 주 정산 예정") */
    List<SettlementItem> findByStoreIdAndSettlementIdIsNull(Long storeId);

    List<SettlementItem> findByConfirmedAtGreaterThanEqualAndConfirmedAtLessThan(LocalDateTime from, LocalDateTime until);

    /** 스토어 최근 구매확정분 — 적용 중인 수수료율 표시 */
    List<SettlementItem> findTop50ByStoreIdOrderByConfirmedAtDesc(Long storeId);
}
