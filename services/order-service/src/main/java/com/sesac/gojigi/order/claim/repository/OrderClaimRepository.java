package com.sesac.gojigi.order.claim.repository;

import com.sesac.gojigi.order.claim.entity.ClaimStatus;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import com.sesac.gojigi.order.claim.entity.OrderClaim;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /** 상품주문의 그 상태 클레임 — 교환 재배송 배송완료 때 RESHIPPED 클레임 찾기 */
    Optional<OrderClaim> findByOrderItemIdAndStatus(Long orderItemId, ClaimStatus status);

    /** 판매자 클레임 목록 — 내 스토어 · statuses · 유형(없으면 전체), 최신 요청 먼저. 상품주문 · 주문(스냅샷)을 함께 읽는다 */
    @Query(value = """
            select c from OrderClaim c join fetch c.orderItem i join fetch i.order o
            where i.storeId = :storeId and c.status in :statuses and (:type is null or c.claimType = :type)
            order by c.requestedAt desc, c.id desc
            """,
            countQuery = """
            select count(c) from OrderClaim c join c.orderItem i
            where i.storeId = :storeId and c.status in :statuses and (:type is null or c.claimType = :type)
            """)
    Page<OrderClaim> searchSeller(@Param("storeId") Long storeId, @Param("statuses") Collection<ClaimStatus> statuses,
                                  @Param("type") ClaimType type, Pageable pageable);

    /** 스토어 클레임의 [유형, 상태, 개수] — 판매자 탭 숫자 */
    @Query("""
            select c.claimType, c.status, count(c) from OrderClaim c
            where c.orderItem.storeId = :storeId group by c.claimType, c.status
            """)
    List<Object[]> countByTypeAndStatus(@Param("storeId") Long storeId);

    /** 내 클레임 — 최신 요청 먼저 (구매자당 수백 건 이하라 페이징 없음) */
    @Query("""
            select c from OrderClaim c join fetch c.orderItem i join fetch i.order
            where c.requesterId = :buyerId order by c.requestedAt desc, c.id desc
            """)
    List<OrderClaim> findMine(@Param("buyerId") Long buyerId);
}
