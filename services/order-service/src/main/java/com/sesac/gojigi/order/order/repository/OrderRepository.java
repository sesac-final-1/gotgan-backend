package com.sesac.gojigi.order.order.repository;

import com.sesac.gojigi.order.order.entity.Order;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** 그날 마지막 주문번호 — 주문번호 = yyyyMMdd-{그날 순번 6자리} (uk_orders_order_no 가 동시 생성 충돌을 막는다) */
    Optional<Order> findTopByOrderNoStartingWithOrderByOrderNoDesc(String datePrefix);
}
