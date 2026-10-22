package com.sesac.gojigi.order.order.repository;

import com.sesac.gojigi.order.order.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
}
