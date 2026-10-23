package com.sesac.gojigi.order.claim.repository;

import com.sesac.gojigi.order.claim.entity.ClaimStatus;
import com.sesac.gojigi.order.claim.entity.OrderClaim;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderClaimRepository extends JpaRepository<OrderClaim, Long> {

    /** 스토어의 그 상태 클레임 수 — 상품주문의 store_id 기준 */
    long countByOrderItemStoreIdAndStatusIn(Long storeId, Collection<ClaimStatus> statuses);
}
