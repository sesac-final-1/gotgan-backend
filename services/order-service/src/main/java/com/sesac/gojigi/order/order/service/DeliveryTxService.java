package com.sesac.gojigi.order.order.service;

import com.sesac.gojigi.order.common.event.EventOutboxService;
import com.sesac.gojigi.order.common.event.OutboxEvent;
import com.sesac.gojigi.order.order.dto.event.OrderItemDeliveredPayload;
import com.sesac.gojigi.order.order.dto.event.OrderItemShippedPayload;
import com.sesac.gojigi.order.order.dto.response.OrderItemResponse;
import com.sesac.gojigi.order.order.entity.ActorType;
import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderItemHistory;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.exception.OrderException;
import com.sesac.gojigi.order.order.repository.OrderItemHistoryRepository;
import com.sesac.gojigi.order.order.repository.OrderItemRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배송 상태 변경 (DLV) — 상품주문 1건 = 짧은 트랜잭션 1개. 상태 변경 · 이력 · 이벤트(아웃박스)를 같은 트랜잭션에.
 * 판매자 버튼과 자동 배송완료가 같은 상품주문을 동시에 바꾸면 @Version 충돌로 한쪽만 반영된다.
 */
@Service
public class DeliveryTxService {

    /** 송장번호 mock 접두 — 프론트 목업과 같은 택배사별 4자리, 모르는 택배사는 7000 */
    private static final Map<String, String> TRACKING_PREFIX = Map.of("CJ대한통운", "6512", "롯데택배", "2470",
            "우체국택배", "6091", "한진택배", "5309");

    private final OrderItemRepository orderItemRepository;
    private final OrderItemHistoryRepository historyRepository;
    private final EventOutboxService outbox;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final Duration autoConfirmAfter;

    public DeliveryTxService(OrderItemRepository orderItemRepository, OrderItemHistoryRepository historyRepository,
                             EventOutboxService outbox, ApplicationEventPublisher eventPublisher, Clock clock,
                             @Value("${app.order.auto-confirm-after}") Duration autoConfirmAfter) {
        this.orderItemRepository = orderItemRepository;
        this.historyRepository = historyRepository;
        this.outbox = outbox;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.autoConfirmAfter = autoConfirmAfter;
    }

    /** 발주확인 — 결제완료 → 배송준비 */
    @Transactional
    public OrderItemResponse prepare(Long storeId, Long sellerId, Long orderItemId) {
        OrderItem item = mine(storeId, orderItemId);
        item.prepare();
        history(item, ActorType.SELLER, sellerId, OrderItemStatus.PAYMENT_DONE, "발주확인");
        return OrderItemResponse.from(item);
    }

    /** 송장 등록 — 배송준비 → 배송중, 송장번호를 비우면 mock 번호. 커밋 후 OrderItemShipped (배송 시작 메일) */
    @Transactional
    public OrderItemResponse ship(Long storeId, Long sellerId, Long orderItemId, String courier, String trackingNo) {
        OrderItem item = mine(storeId, orderItemId);
        item.ship(courier, trackingNo == null || trackingNo.isBlank() ? mockTrackingNo(courier, orderItemId) : trackingNo,
                LocalDateTime.now(clock));
        history(item, ActorType.SELLER, sellerId, OrderItemStatus.PREPARING, "송장 등록 " + courier + " " + item.getTrackingNo());
        outbox.enqueue("OrderItemShipped", OutboxEvent.AggregateType.ORDER_ITEM, item.getItemNo(),
                OrderItemShippedPayload.from(item));
        return OrderItemResponse.from(item);
    }

    /** 교환 재배송이 배송완료됨 — 같은 트랜잭션에서 claim 이 교환 클레임을 완료한다 (ClaimEventListener, order → claim 참조를 만들지 않게) */
    public record ExchangeRedelivered(Long orderItemId) {
    }

    /** 배송완료 (판매자 버튼) — 배송중 → 배송완료, 교환 재배송 중이면 교환 완료 */
    @Transactional
    public OrderItemResponse deliver(Long storeId, Long sellerId, Long orderItemId) {
        return deliver(mine(storeId, orderItemId), ActorType.SELLER, sellerId, "판매자 배송완료");
    }

    /** 자동 배송완료 (스케줄러) — 택배 추적 대신. 그 사이 판매자가 먼저 처리했으면 ORDER_INVALID_STATUS */
    @Transactional
    public void deliverBySystem(Long orderItemId) {
        OrderItem item = orderItemRepository.findById(orderItemId)
                .orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_ITEM_NOT_FOUND));
        deliver(item, ActorType.SYSTEM, null, "자동 배송완료");
    }

    /** 자동 배송완료 대상 — 발송(교환은 재배송) 후 before 이전부터 배송 중인 상품주문 */
    @Transactional(readOnly = true)
    public List<Long> findShippedBefore(LocalDateTime before, int limit) {
        List<Long> ids = new ArrayList<>(orderItemRepository.findIdsByStatusAndShippedAtBefore(OrderItemStatus.SHIPPING,
                before, Limit.of(limit)));
        ids.addAll(orderItemRepository.findReshippedIdsBefore(before, Limit.of(limit)));
        return ids;
    }

    private OrderItemResponse deliver(OrderItem item, ActorType actor, Long actorId, String reason) {
        boolean exchange = item.isReshipping();
        OrderItemStatus from = item.getStatus();
        item.deliverOrRedeliver(LocalDateTime.now(clock));
        history(item, actor, actorId, from, exchange ? reason + " (교환 재배송)" : reason);
        if (exchange) {
            eventPublisher.publishEvent(new ExchangeRedelivered(item.getId()));
        }
        outbox.enqueue("OrderItemDelivered", OutboxEvent.AggregateType.ORDER_ITEM, item.getItemNo(),
                OrderItemDeliveredPayload.from(item, autoConfirmAfter));
        return OrderItemResponse.from(item);
    }

    /** 내 스토어 상품주문 — 없거나 남의 스토어면 같은 404 */
    private OrderItem mine(Long storeId, Long orderItemId) {
        return orderItemRepository.findById(orderItemId).filter(item -> item.getStoreId().equals(storeId))
                .orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_ITEM_NOT_FOUND));
    }

    private void history(OrderItem item, ActorType actor, Long actorId, OrderItemStatus from, String reason) {
        historyRepository.save(OrderItemHistory.of(item, actor, actorId, from, item.getStatus(), reason));
    }

    /** 송장번호 mock — 외부 택배 API 가 없어 택배사 접두 + 상품주문 ID 8자리 (상품주문마다 다르고 다시 만들어도 같다) */
    public static String mockTrackingNo(String courier, Long orderItemId) {
        return TRACKING_PREFIX.getOrDefault(courier, "7000") + String.format("%08d", orderItemId);
    }
}
