package com.sesac.gojigi.order.order.repository;

import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /** 여러 주문의 상품주문을 한 번에 — 주문마다 따로 읽는 N+1 을 피한다 (상품주문 번호 순) */
    List<OrderItem> findByOrderIdInOrderByIdAsc(Collection<Long> orderIds);

    List<OrderItem> findByOrderIdOrderByIdAsc(Long orderId);

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
