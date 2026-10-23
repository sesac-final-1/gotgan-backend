package com.sesac.gojigi.order.order.repository;

import com.sesac.gojigi.order.order.entity.OrderItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /** 여러 주문의 상품주문을 한 번에 — 주문마다 따로 읽는 N+1 을 피한다 (상품주문 번호 순) */
    List<OrderItem> findByOrderIdInOrderByIdAsc(Collection<Long> orderIds);

    List<OrderItem> findByOrderIdOrderByIdAsc(Long orderId);
}
