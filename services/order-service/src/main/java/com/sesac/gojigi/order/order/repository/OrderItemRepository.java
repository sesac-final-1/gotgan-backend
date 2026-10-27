package com.sesac.gojigi.order.order.repository;

import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /** 여러 주문의 상품주문을 한 번에 — 주문마다 따로 읽는 N+1 을 피한다 (상품주문 번호 순) */
    List<OrderItem> findByOrderIdInOrderByIdAsc(Collection<Long> orderIds);

    List<OrderItem> findByOrderIdOrderByIdAsc(Long orderId);

    /**
     * 판매자 주문 목록 — 내 스토어 · statuses 상태, q 가 있으면 상품주문번호 · 구매자 · 수령인 · 상품명 · 송장번호 부분 일치.
     * 최신 주문 먼저. 주문(스냅샷)을 함께 읽어 행마다 다시 조회하지 않는다.
     */
    @Query(value = """
            select i from OrderItem i join fetch i.order o
            where i.storeId = :storeId and i.status in :statuses
              and (:q is null or i.itemNo like concat('%', :q, '%') or o.ordererDisplayName like concat('%', :q, '%')
                   or o.receiverName like concat('%', :q, '%') or i.productName like concat('%', :q, '%')
                   or i.trackingNo like concat('%', :q, '%'))
            order by o.createdAt desc, i.id desc
            """,
            countQuery = """
            select count(i) from OrderItem i join i.order o
            where i.storeId = :storeId and i.status in :statuses
              and (:q is null or i.itemNo like concat('%', :q, '%') or o.ordererDisplayName like concat('%', :q, '%')
                   or o.receiverName like concat('%', :q, '%') or i.productName like concat('%', :q, '%')
                   or i.trackingNo like concat('%', :q, '%'))
            """)
    Page<OrderItem> searchSeller(@Param("storeId") Long storeId, @Param("statuses") Collection<OrderItemStatus> statuses,
                                 @Param("q") String q, Pageable pageable);

    /** 스토어 상품주문의 상태별 개수 [상태, 개수] — 판매자 주문 탭 */
    @Query("select i.status, count(i) from OrderItem i where i.storeId = :storeId group by i.status")
    List<Object[]> countByStatus(@Param("storeId") Long storeId);

    /** 자동 배송완료 대상 — 그 상태로 shipped_at 이 before 보다 이른 상품주문 ID (오래된 것부터) */
    @Query("select i.id from OrderItem i where i.status = :status and i.shippedAt < :before order by i.id")
    List<Long> findIdsByStatusAndShippedAtBefore(@Param("status") OrderItemStatus status,
                                                 @Param("before") LocalDateTime before, Limit limit);

    /** 스토어의 그 상태 상품주문 수 — idx_order_item_store_status (store_id, status, created_at) */
    long countByStoreIdAndStatus(Long storeId, OrderItemStatus status);

    /** 스토어 매출 — 결제 시각이 [from, to) 인 주문의 상품주문 금액 합계 (excluded 상태 제외) */
    @Query("""
            select coalesce(sum(i.itemAmt), 0) from OrderItem i
            where i.storeId = :storeId and i.status not in :excluded
              and i.order.paidAt >= :from and i.order.paidAt < :to
            """)
    long sumStoreSales(@Param("storeId") Long storeId, @Param("excluded") Collection<OrderItemStatus> excluded,
                       @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 전체 거래액 — 상품주문 금액 합계 (excluded 상태 제외) */
    @Query("select coalesce(sum(i.itemAmt), 0) from OrderItem i where i.status not in :excluded")
    long sumItemAmt(@Param("excluded") Collection<OrderItemStatus> excluded);
}
