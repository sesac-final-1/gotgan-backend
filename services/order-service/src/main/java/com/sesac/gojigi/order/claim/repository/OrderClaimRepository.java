package com.sesac.gojigi.order.claim.repository;

import com.sesac.gojigi.order.claim.entity.ClaimStatus;
import com.sesac.gojigi.order.claim.entity.OrderClaim;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderClaimRepository extends JpaRepository<OrderClaim, Long> {

    /** 스토어의 그 상태 클레임 수 — 상품주문의 store_id 기준 */
    long countByOrderItemStoreIdAndStatusIn(Long storeId, Collection<ClaimStatus> statuses);

    /** 이 상품주문에 진행 중 클레임이 있는지 — 한 상품주문에 진행 중 클레임 1개 (CLM-06) */
    boolean existsByOrderItemIdAndStatusIn(Long orderItemId, Collection<ClaimStatus> statuses);

    /** 주문에서 이미 환불했거나 환불 중인 현금 합계 — 남은 결제 금액을 넘겨 부분 취소하지 않게 */
    @Query("""
            select coalesce(sum(c.refundAmt), 0) from OrderClaim c
            where c.orderItem.order.id = :orderId and c.status in :statuses
            """)
    long sumRefundAmt(@Param("orderId") Long orderId, @Param("statuses") Collection<ClaimStatus> statuses);
}
