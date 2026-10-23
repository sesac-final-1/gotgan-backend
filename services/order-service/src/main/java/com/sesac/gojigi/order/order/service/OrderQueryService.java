package com.sesac.gojigi.order.order.service;

import com.sesac.gojigi.order.order.client.PaymentClientAdapter;
import com.sesac.gojigi.order.order.client.dto.PaymentResponse;
import com.sesac.gojigi.order.order.dto.response.OrderDetailResponse;
import com.sesac.gojigi.order.order.dto.response.OrderListResponse;
import com.sesac.gojigi.order.order.dto.response.OrderSummaryResponse;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.exception.OrderException;
import com.sesac.gojigi.order.order.repository.OrderItemRepository;
import com.sesac.gojigi.order.order.repository.OrderRepository;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 구매자 내 주문 조회 (ORD-08) — 본인 주문만 */
@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentClientAdapter payment;

    /** 내 주문 전체(최신순) + 상품주문 상태별 개수 — 주문 1번 + 상품주문 1번, 두 번만 읽는다 */
    @Transactional(readOnly = true)
    public OrderListResponse getMyOrders(Long buyerId) {
        List<Order> orders = orderRepository.findByBuyerIdOrderByCreatedAtDescIdDesc(buyerId);
        Map<OrderItemStatus, Long> counts = new EnumMap<>(OrderItemStatus.class);
        for (OrderItemStatus s : OrderItemStatus.values()) {
            counts.put(s, 0L);
        }
        if (orders.isEmpty()) {
            return new OrderListResponse(counts, List.of());
        }
        Map<Long, List<OrderItem>> itemsByOrder = orderItemRepository
                .findByOrderIdInOrderByIdAsc(orders.stream().map(Order::getId).toList()).stream()
                .collect(Collectors.groupingBy(item -> item.getOrder().getId()));
        List<OrderSummaryResponse> summaries = orders.stream().map(order -> {
            List<OrderItem> items = itemsByOrder.getOrDefault(order.getId(), List.of());
            items.forEach(item -> counts.merge(item.getStatus(), 1L, Long::sum));
            return OrderSummaryResponse.of(order, items);
        }).toList();
        return new OrderListResponse(counts, summaries);
    }

    /**
     * 내 주문 상세 — 없는 주문번호 · 다른 회원의 주문은 같은 404.
     * payment 를 부르므로 트랜잭션을 걸지 않는다(원격 호출은 트랜잭션 밖) — 조회 두 번은 각각 짧은 읽기.
     */
    public OrderDetailResponse getMyOrder(Long buyerId, String orderNo) {
        Order order = orderRepository.findByOrderNoAndBuyerId(orderNo, buyerId)
                .orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));
        List<OrderItem> items = orderItemRepository.findByOrderIdOrderByIdAsc(order.getId());
        PaymentResponse paid = order.getPayAmt() > 0 ? payment.find(orderNo) : null;   // 0원 주문은 결제가 없다
        return OrderDetailResponse.of(order, items, paid);
    }
}
