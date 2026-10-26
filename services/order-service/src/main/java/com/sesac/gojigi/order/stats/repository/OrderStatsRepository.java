package com.sesac.gojigi.order.stats.repository;

import com.sesac.gojigi.order.claim.entity.ClaimStatus;
import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import com.sesac.gojigi.order.order.entity.OrderStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 상품주문 · 클레임을 함께 보는 통계 쿼리 — order 저장소에 두면 order → claim 참조가 생겨 stats 에 둔다 */
public interface OrderStatsRepository extends Repository<OrderItem, Long> {

    /** 스토어의 진행 중 상품주문 수 — 상태가 statuses 이거나 진행 중 클레임이 걸린 상품주문 (한 건은 한 번만) */
    @Query("""
            select count(i) from OrderItem i
            where i.storeId = :storeId
              and (i.status in :statuses
                   or exists (select c.id from OrderClaim c where c.orderItem = i and c.status in :claimStatuses))
            """)
    long countInProgress(@Param("storeId") Long storeId, @Param("statuses") Collection<OrderItemStatus> statuses,
                         @Param("claimStatuses") Collection<ClaimStatus> claimStatuses);

    /** 결제일(paid_at 은 KST)별 상품주문 금액 합계 [결제일, 합계] — excluded 상태 제외, storeId 가 null 이면 전체. 거래 없는 날은 행이 없다 */
    @Query("""
            select cast(i.order.paidAt as LocalDate), sum(i.itemAmt) from OrderItem i
            where (:storeId is null or i.storeId = :storeId) and i.status not in :excluded
              and i.order.paidAt >= :from and i.order.paidAt < :to
            group by cast(i.order.paidAt as LocalDate)
            """)
    List<Object[]> sumDailyTradeAmt(@Param("storeId") Long storeId,
                                    @Param("excluded") Collection<OrderItemStatus> excluded,
                                    @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 결제일별 status(PAID) 주문 수 [결제일, 건수] — storeId 가 있으면 그 스토어의 매출 상품주문(excluded 아님)이 든 주문만 */
    @Query("""
            select cast(o.paidAt as LocalDate), count(o) from Order o
            where o.status = :status and o.paidAt >= :from and o.paidAt < :to
              and (:storeId is null or exists (select i.id from OrderItem i
                                               where i.order = o and i.storeId = :storeId and i.status not in :excluded))
            group by cast(o.paidAt as LocalDate)
            """)
    List<Object[]> countDailyOrders(@Param("storeId") Long storeId, @Param("status") OrderStatus status,
                                    @Param("excluded") Collection<OrderItemStatus> excluded,
                                    @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
