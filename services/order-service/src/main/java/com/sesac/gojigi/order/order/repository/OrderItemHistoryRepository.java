package com.sesac.gojigi.order.order.repository;

import com.sesac.gojigi.order.order.entity.OrderItemHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 상품주문 상태 변경 이력 — INSERT 전용, 상태 변경과 같은 트랜잭션에서 저장 */
public interface OrderItemHistoryRepository extends JpaRepository<OrderItemHistory, Long> {

    List<OrderItemHistory> findByOrderItemIdOrderByIdAsc(Long orderItemId);
}
