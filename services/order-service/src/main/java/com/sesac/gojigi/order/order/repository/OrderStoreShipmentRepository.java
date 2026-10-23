package com.sesac.gojigi.order.order.repository;

import com.sesac.gojigi.order.order.entity.OrderStoreShipment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderStoreShipmentRepository extends JpaRepository<OrderStoreShipment, Long> {

    /** 주문의 그 스토어 배송비 — 스토어 마지막 상품주문을 취소하면 함께 환불 */
    Optional<OrderStoreShipment> findByOrderIdAndStoreId(Long orderId, Long storeId);
}
