package com.sesac.gojigi.order.order.service;

import com.sesac.gojigi.order.order.client.dto.ProductOptionResponse;
import com.sesac.gojigi.order.order.client.dto.UserSummaryResponse;
import com.sesac.gojigi.order.order.dto.request.OrderCreateRequest;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderStoreShipment;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.exception.OrderException;
import com.sesac.gojigi.order.order.repository.OrderItemRepository;
import com.sesac.gojigi.order.order.repository.OrderRepository;
import com.sesac.gojigi.order.order.repository.OrderStoreShipmentRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 로컬 트랜잭션 모음 — Saga(PlaceOrderSaga)는 원격 호출을 트랜잭션 밖에서 하고, DB 변경은 여기 메서드 하나씩 짧게 (가이드라인 06장).
 */
@Service
@RequiredArgsConstructor
public class OrderTxService {

    private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.BASIC_ISO_DATE;   // 20261022

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStoreShipmentRepository orderStoreShipmentRepository;
    private final Clock clock;

    /** 주문 PENDING + 상품주문 · 스토어별 배송비 스냅샷 (Saga 1단계 ORDER_CREATED) */
    public record PendingOrder(Order order, List<OrderItem> items) {
    }

    /**
     * 주문번호는 그날 순번이라 동시에 만들면 uk_orders_order_no 충돌이 날 수 있다 — 호출 쪽이 DataIntegrityViolationException 이면 다시 부른다.
     */
    @Transactional
    public PendingOrder createPending(UserSummaryResponse buyer, OrderCreateRequest request, OrderPricing.Result priced,
                                     Map<Long, String> storeNames) {
        String orderNo = nextOrderNo(LocalDate.now(clock));
        Order order = orderRepository.saveAndFlush(Order.builder()
                .buyerId(buyer.userId()).orderNo(orderNo)
                .ordererName(buyer.name()).ordererDisplayName(displayName(buyer)).ordererEmail(buyer.email())
                .receiverName(request.receiverName()).receiverPhone(request.receiverPhone()).zipcode(request.zipcode())
                .address1(request.address1()).address2(request.address2()).deliveryMemo(request.deliveryMemo())
                .totalProductAmt(priced.totalProductAmt()).totalShippingFee(priced.totalShippingFee())
                .usedPoint(priced.usedPoint()).payAmt(priced.payAmt())
                .build());
        priced.shippingFeeByStore().forEach((storeId, fee) ->
                orderStoreShipmentRepository.save(OrderStoreShipment.of(order, storeId, fee)));
        List<OrderItem> items = new ArrayList<>();
        for (int i = 0; i < priced.lines().size(); i++) {
            OrderPricing.PricedLine line = priced.lines().get(i);
            items.add(snapshot(order, String.format("%s-%02d", orderNo, i + 1), line,
                    storeNames.get(line.line().option().storeId())));
        }
        return new PendingOrder(order, orderItemRepository.saveAll(items));
    }

    /** 전액 포인트(0원) 주문 — 결제창 없이 바로 PAID, 상품주문은 주문완료 → 결제완료 */
    @Transactional
    public void markPaid(Long orderId) {
        getOrder(orderId).markPaid(LocalDateTime.now(clock));
        orderItemRepository.findByOrderIdOrderByIdAsc(orderId).forEach(OrderItem::markPaymentDone);
    }

    /** Saga 보상을 마친 주문 — 상품주문은 주문완료(ORDERED) 그대로 */
    @Transactional
    public void markFailed(Long orderId) {
        getOrder(orderId).markFailed();
    }

    private Order getOrder(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_INVALID_STATUS));
    }

    private String nextOrderNo(LocalDate today) {
        String prefix = today.format(ORDER_DATE) + "-";
        long last = orderRepository.findTopByOrderNoStartingWithOrderByOrderNoDesc(prefix)
                .map(o -> Long.parseLong(o.getOrderNo().substring(prefix.length())))
                .orElse(0L);
        return prefix + String.format("%06d", last + 1);
    }

    private static OrderItem snapshot(Order order, String itemNo, OrderPricing.PricedLine priced, String storeName) {
        ProductOptionResponse option = priced.line().option();
        return OrderItem.builder()
                .order(order).storeId(option.storeId()).productId(option.productId())
                .productOptionId(option.productOptionId()).categoryId(option.categoryId())
                .topCategoryId(option.topCategoryId()).itemNo(itemNo).storeName(storeName)
                .productName(option.productName()).optionName(option.optionName()).thumbnailUrl(option.imageUrl())
                .unitPrice(option.unitPrice()).quantity(priced.line().quantity()).itemAmt(priced.itemAmt())
                .usedPoint(priced.usedPoint()).earnedPoint(priced.earnedPoint())
                .build();
    }

    /** 공개 표시용 이름 — 닉네임이 있으면 닉네임, 없으면 실명 가운데를 가린다 (홍길동 → 홍*동, 홍길 → 홍*) */
    static String displayName(UserSummaryResponse buyer) {
        if (buyer.nick() != null && !buyer.nick().isBlank()) {
            return buyer.nick().trim();
        }
        String name = buyer.name().trim();
        if (name.length() <= 1) {
            return "*";
        }
        if (name.length() == 2) {
            return name.charAt(0) + "*";
        }
        return name.charAt(0) + "*".repeat(name.length() - 2) + name.charAt(name.length() - 1);
    }
}
