package com.sesac.gojigi.order.order.repository;

import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** 그날 마지막 주문번호 — 주문번호 = yyyyMMdd-{그날 순번 6자리} (uk_orders_order_no 가 동시 생성 충돌을 막는다) */
    Optional<Order> findTopByOrderNoStartingWithOrderByOrderNoDesc(String datePrefix);

    /** 내 주문 최신순 — idx_orders_buyer_created (buyer_id, created_at) */
    List<Order> findByBuyerIdOrderByCreatedAtDescIdDesc(Long buyerId);

    /** 본인 주문 한 건 — 다른 회원의 주문번호면 비어 있음 */
    Optional<Order> findByOrderNoAndBuyerId(String orderNo, Long buyerId);

    long countByStatus(OrderStatus status);

    /** 클레임 접수 — 같은 주문의 취소가 동시에 와도 환불 금액(남은 결제 금액 · 스토어 마지막 여부)을 차례로 계산하도록 행 잠금 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.orderNo = :orderNo and o.buyerId = :buyerId")
    Optional<Order> findMineForUpdate(@Param("orderNo") String orderNo, @Param("buyerId") Long buyerId);
}
