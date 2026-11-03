package com.sesac.gojigi.settlement.settlement.repository;

import com.sesac.gojigi.settlement.settlement.entity.Settlement;
import com.sesac.gojigi.settlement.settlement.entity.SettlementStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    Optional<Settlement> findByStoreIdAndWeekStart(Long storeId, LocalDate weekStart);

    List<Settlement> findByWeekStartOrderByStoreIdAsc(LocalDate weekStart);

    List<Settlement> findByStoreIdOrderByWeekStartDesc(Long storeId);

    List<Settlement> findByStoreIdAndStatus(Long storeId, SettlementStatus status);

    /** 관리자 대시보드 지급예정 · 보류 — [status, 건수, 지급액 합] (행을 읽지 않고 DB 에서 합산) */
    @Query("""
            select s.status, count(s), coalesce(sum(s.payoutAmt), 0) from Settlement s
             where s.status in :statuses group by s.status
            """)
    List<Object[]> sumByStatusIn(@Param("statuses") Collection<SettlementStatus> statuses);

    /** 관리자 대시보드 이번 달 지급액 — from 이후 지급 완료분 지급액 합 */
    @Query("""
            select coalesce(sum(s.payoutAmt), 0) from Settlement s
             where s.status = com.sesac.gojigi.settlement.settlement.entity.SettlementStatus.PAID and s.paidAt >= :from
            """)
    long sumPaidSince(@Param("from") LocalDateTime from);
}
