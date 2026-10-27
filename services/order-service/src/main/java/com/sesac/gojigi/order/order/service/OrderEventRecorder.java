package com.sesac.gojigi.order.order.service;

import com.sesac.gojigi.order.common.event.EventOutboxService;
import com.sesac.gojigi.order.common.event.OutboxEvent;
import com.sesac.gojigi.order.order.dto.event.OrderPaidPayload;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.repository.OrderItemRepository;
import com.sesac.gojigi.order.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * OrderPaid 발행 (ORD-09) — 결제 확정(OrderTxService#markPaid: 결제 확인 · 0원 주문 · Saga 복구 모두 이 경로) 트랜잭션 안에서 아웃박스에 남긴다.
 * 소비자: user(실결제 5% 포인트 적립 — items[].earnedPoint) · 알림 Lambda(주문 확인 메일). 커밋 후 SNS 로 나간다 (EventOutboxService).
 */
@Component
@RequiredArgsConstructor
public class OrderEventRecorder {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final EventOutboxService outbox;

    /** 주문 PAID 와 같은 트랜잭션 — 결제 확정이 롤백되면 이벤트도 남지 않는다 */
    @EventListener
    public void onPaid(OrderTxService.Paid paid) {
        Order order = orderRepository.findById(paid.orderId()).orElseThrow();
        outbox.enqueue("OrderPaid", OutboxEvent.AggregateType.ORDER, order.getOrderNo(),
                OrderPaidPayload.of(order, orderItemRepository.findByOrderIdOrderByIdAsc(order.getId())));
    }
}
