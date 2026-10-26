package com.sesac.gojigi.order.saga.service;

import com.sesac.gojigi.order.claim.entity.ClaimReasonCode;
import com.sesac.gojigi.order.claim.entity.ClaimStatus;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import com.sesac.gojigi.order.claim.entity.OrderClaim;
import com.sesac.gojigi.order.claim.exception.ClaimErrorCode;
import com.sesac.gojigi.order.claim.exception.ClaimException;
import com.sesac.gojigi.order.claim.repository.OrderClaimRepository;
import com.sesac.gojigi.order.order.entity.ActorType;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderItemHistory;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import com.sesac.gojigi.order.order.entity.OrderStatus;
import com.sesac.gojigi.order.order.entity.OrderStoreShipment;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.exception.OrderException;
import com.sesac.gojigi.order.order.repository.OrderItemHistoryRepository;
import com.sesac.gojigi.order.order.repository.OrderItemRepository;
import com.sesac.gojigi.order.order.repository.OrderRepository;
import com.sesac.gojigi.order.order.repository.OrderStoreShipmentRepository;
import com.sesac.gojigi.order.saga.entity.SagaInstance;
import com.sesac.gojigi.order.saga.entity.SagaType;
import com.sesac.gojigi.order.saga.repository.SagaInstanceRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제완료 단계 취소 Saga(CLM-01)의 로컬 트랜잭션 — 원격 호출은 CancelOrderItemSaga 가 트랜잭션 밖에서 한다.
 * 클레임 · 상품주문 상태 · 이력 · saga_instance 를 한 트랜잭션으로 만들어야 Saga 가 "클레임만 있고 진행 기록이 없는" 상태로 남지 않는다
 * — 그래서 claim 이 아니라 saga 패키지에 둔다 (claim → saga 참조를 만들지 않게).
 */
@Service
@RequiredArgsConstructor
public class CancelItemTxService {

    /** 진행 중 클레임 — 요청됐거나 승인 후 환불 · 재배송 중 */
    private static final Set<ClaimStatus> OPEN = Set.of(ClaimStatus.REQUESTED, ClaimStatus.APPROVED);
    /** 환불했거나 환불 중인 클레임 — 남은 결제 금액 계산에서 빼는 금액 */
    private static final Set<ClaimStatus> REFUNDING = Set.of(ClaimStatus.APPROVED, ClaimStatus.COMPLETED);
    /** 스토어 배송이 더 이상 없는 상태 — 이 상품주문이 스토어 마지막인지 볼 때 */
    private static final Set<OrderItemStatus> NOT_SHIPPING = Set.of(OrderItemStatus.CANCEL_REQUESTED,
            OrderItemStatus.CANCELLED);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStoreShipmentRepository shipmentRepository;
    private final OrderItemHistoryRepository historyRepository;
    private final OrderClaimRepository claimRepository;
    private final SagaInstanceRepository sagaInstanceRepository;
    private final Clock clock;

    /** 취소 Saga 가 단계마다 쓰는 값 — 클레임을 만들 때 정해 두고 재시도해도 같은 값 */
    public record CancelContext(Long sagaInstanceId, String sagaId, Long claimId, Long orderItemId, String orderNo,
                                Long buyerId, Long productOptionId, int quantity, long refundAmt, long restorePoint,
                                long earnedPoint) {
    }

    /**
     * 취소 접수 — 클레임(자동 승인) · 상품주문 CANCEL_REQUESTED · 이력 · 취소 Saga 를 한 트랜잭션으로.
     * 환불 = 현금 min(상품주문 금액 - 사용 포인트 + 스토어 마지막이면 배송비, 남은 결제 금액), 현금으로 못 돌려준 몫 + 사용 포인트는 포인트로.
     * 주문 행을 잠가 같은 주문의 취소가 동시에 와도 남은 금액 · 스토어 마지막 여부를 차례로 계산한다.
     */
    @Transactional
    public CancelContext open(Long buyerId, String orderNo, Long orderItemId, ClaimReasonCode reasonCode,
                              String reasonDetail) {
        Order order = orderRepository.findMineForUpdate(orderNo, buyerId)
                .orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));
        List<OrderItem> items = orderItemRepository.findByOrderIdOrderByIdAsc(order.getId());
        OrderItem item = items.stream().filter(i -> i.getId().equals(orderItemId)).findFirst()
                .orElseThrow(() -> new ClaimException(ClaimErrorCode.CLAIM_ITEM_NOT_FOUND));
        if (claimRepository.existsByOrderItemIdAndStatusIn(orderItemId, OPEN)) {
            throw new ClaimException(ClaimErrorCode.CLAIM_IN_PROGRESS);
        }
        if (order.getStatus() != OrderStatus.PAID || item.getStatus() != OrderItemStatus.PAYMENT_DONE) {
            throw new ClaimException(ClaimErrorCode.CLAIM_NOT_CANCELLABLE);   // 배송준비 취소 요청(CLM-02) · 반품은 별도
        }

        long itemCash = item.getItemAmt() - item.getUsedPoint();
        long shippingFee = lastOfStore(items, item) ? storeShippingFee(order, item) : 0L;
        long cashLeft = order.getPayAmt() - claimRepository.sumRefundAmt(order.getId(), REFUNDING);
        long refundAmt = Math.min(itemCash + shippingFee, cashLeft);
        long restorePoint = item.getUsedPoint() + (itemCash + shippingFee - refundAmt);   // 배송비를 포인트로 냈던 몫

        LocalDateTime now = LocalDateTime.now(clock);
        OrderClaim claim = OrderClaim.builder().orderItem(item).requesterId(buyerId).claimType(ClaimType.CANCEL)
                .reasonCode(reasonCode).reasonDetail(reasonDetail).refundAmt(refundAmt).restorePoint(restorePoint)
                .requestedAt(now).build();
        claim.approve(now);   // 결제완료 단계 취소는 판매자 승인 없이 즉시
        claimRepository.save(claim);
        item.requestCancel();
        historyRepository.save(OrderItemHistory.of(item, ActorType.BUYER, buyerId, OrderItemStatus.PAYMENT_DONE,
                OrderItemStatus.CANCEL_REQUESTED, "구매자 즉시 취소"));
        SagaInstance saga = sagaInstanceRepository.save(SagaInstance.forClaim(claim, UUID.randomUUID().toString(),
                SagaType.CANCEL_ITEM));
        return context(saga, claim, item, order);
    }

    /** 재시도 · 복구 때 — 저장해 둔 클레임 값으로 다시 만든다 */
    @Transactional(readOnly = true)
    public CancelContext load(Long sagaInstanceId) {
        SagaInstance saga = sagaInstanceRepository.findById(sagaInstanceId)
                .orElseThrow(() -> new IllegalStateException("saga_instance 없음 - id=" + sagaInstanceId));
        OrderClaim claim = saga.getOrderClaim();
        return context(saga, claim, claim.getOrderItem(), saga.getOrder());
    }

    /**
     * 취소 완료 — 상품주문 CANCELLED · 이력 · 클레임 COMPLETED · (모든 상품주문이 취소되면) 주문 CANCELLED · Saga 완료.
     * TODO(Outbox · ORD-11): OrderItemCancelled 이벤트를 같은 트랜잭션에 기록 (refundAmt · restoredPoint · revokedPoint)
     */
    @Transactional
    public void finish(Long sagaInstanceId) {
        SagaInstance saga = sagaInstanceRepository.findById(sagaInstanceId)
                .orElseThrow(() -> new IllegalStateException("saga_instance 없음 - id=" + sagaInstanceId));
        OrderClaim claim = saga.getOrderClaim();
        OrderItem item = claim.getOrderItem();
        LocalDateTime now = LocalDateTime.now(clock);
        item.cancelled();
        historyRepository.save(OrderItemHistory.of(item, ActorType.SYSTEM, null, OrderItemStatus.CANCEL_REQUESTED,
                OrderItemStatus.CANCELLED, "취소 완료 — 환불 " + claim.getRefundAmt() + "원 · 포인트 " + claim.getRestorePoint()));
        claim.complete(now);
        Order order = saga.getOrder();
        boolean allCancelled = orderItemRepository.findByOrderIdOrderByIdAsc(order.getId()).stream()
                .allMatch(i -> i.getStatus() == OrderItemStatus.CANCELLED);
        if (allCancelled) {
            order.markCancelled();
        }
        saga.complete();
    }

    /** 같은 스토어의 다른 상품주문이 모두 취소됐거나 취소 중이면 — 이 상품주문이 마지막이라 배송할 게 없다 */
    private static boolean lastOfStore(List<OrderItem> items, OrderItem item) {
        return items.stream()
                .filter(i -> i.getStoreId().equals(item.getStoreId()) && !i.getId().equals(item.getId()))
                .allMatch(i -> NOT_SHIPPING.contains(i.getStatus()));
    }

    private long storeShippingFee(Order order, OrderItem item) {
        return shipmentRepository.findByOrderIdAndStoreId(order.getId(), item.getStoreId())
                .map(OrderStoreShipment::getShippingFee).orElse(0L);
    }

    private static CancelContext context(SagaInstance saga, OrderClaim claim, OrderItem item, Order order) {
        return new CancelContext(saga.getId(), saga.getSagaId(), claim.getId(), item.getId(), order.getOrderNo(),
                order.getBuyerId(), item.getProductOptionId(), item.getQuantity(), claim.getRefundAmt(),
                claim.getRestorePoint(), item.getEarnedPoint());
    }
}
