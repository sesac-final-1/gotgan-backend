package com.sesac.gojigi.settlement.settlement.repository;

import com.sesac.gojigi.settlement.settlement.entity.SettlementItem;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
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

    /** 지난 주차에 구매확정됐는데 아직 정산서에 연결되지 않은 상품주문 — 늦게 들어온 구매확정 (이월 판단 대상) */
    @Query("""
            select i from SettlementItem i
             where i.settlementId is null and i.confirmedAt < :until
             order by i.storeId, i.confirmedAt
            """)
    List<SettlementItem> findUnassignedBefore(@Param("until") LocalDateTime until);

    /** 스토어의 집계 전 구매확정분 (판매자 대시보드 "이번 주 정산 예정") */
    List<SettlementItem> findByStoreIdAndSettlementIdIsNull(Long storeId);

    /** 관리자 대시보드 이번 달 수수료 수익 — 기간 [from, until) 판매수수료 합 (행을 읽지 않고 DB 에서 합산) */
    @Query("""
            select coalesce(sum(i.commissionAmt), 0) from SettlementItem i
             where i.confirmedAt >= :from and i.confirmedAt < :until
            """)
    long sumCommission(@Param("from") LocalDateTime from, @Param("until") LocalDateTime until);

    /** 관리자 대시보드 카테고리별 거래액 — [categoryId, 건수, 매출 합, 수수료 합], 매출 많은 순 */
    @Query("""
            select i.categoryId, count(i), sum(i.itemAmt), sum(i.commissionAmt) from SettlementItem i
             where i.confirmedAt >= :from and i.confirmedAt < :until
             group by i.categoryId order by sum(i.itemAmt) desc
            """)
    List<Object[]> sumByCategory(@Param("from") LocalDateTime from, @Param("until") LocalDateTime until);

    /** 관리자 대시보드 스토어 거래액 순위 — [storeId, 건수, 매출 합, 수수료 합], 매출 많은 순 · pageable 로 상위 N */
    @Query("""
            select i.storeId, count(i), sum(i.itemAmt), sum(i.commissionAmt) from SettlementItem i
             where i.confirmedAt >= :from and i.confirmedAt < :until
             group by i.storeId order by sum(i.itemAmt) desc
            """)
    List<Object[]> sumByStore(@Param("from") LocalDateTime from, @Param("until") LocalDateTime until, Pageable top);

    /** 스토어 최근 구매확정분 — 적용 중인 수수료율 표시 */
    List<SettlementItem> findTop50ByStoreIdOrderByConfirmedAtDesc(Long storeId);
}
