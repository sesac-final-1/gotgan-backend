package com.sesac.gojigi.order.stats.repository;

import com.sesac.gojigi.order.claim.entity.ClaimStatus;
import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import java.util.Collection;
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
}
