package com.sesac.gojigi.order.claim.service;

import com.sesac.gojigi.order.claim.dto.request.ClaimCreateRequest;
import com.sesac.gojigi.order.claim.dto.response.ClaimResponse;
import com.sesac.gojigi.order.claim.entity.ClaimStatus;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import com.sesac.gojigi.order.claim.entity.OrderClaim;
import com.sesac.gojigi.order.claim.exception.ClaimErrorCode;
import com.sesac.gojigi.order.claim.exception.ClaimException;
import com.sesac.gojigi.order.claim.repository.OrderClaimRepository;
import com.sesac.gojigi.order.claim.service.ClaimRefundPolicy.Refund;
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
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 클레임 로컬 트랜잭션 — 클레임 대상(주문 · 상품주문 · 남은 결제 금액 · 스토어 마지막 여부) 조회와 신청 가능 여부 검사, 반품 · 교환 접수.
 * 취소 접수는 Saga 를 함께 만들어야 해서 saga 패키지의 CancelItemTxService 가 lock() 을 같은 트랜잭션에서 부른다.
 */
@Service
public class ClaimTxService {

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
    private final Clock clock;
    private final Duration returnWindow;

    public ClaimTxService(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                          OrderStoreShipmentRepository shipmentRepository, OrderItemHistoryRepository historyRepository,
                          OrderClaimRepository claimRepository, Clock clock,
                          @Value("${app.order.return-window:7d}") Duration returnWindow) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.shipmentRepository = shipmentRepository;
        this.historyRepository = historyRepository;
        this.claimRepository = claimRepository;
        this.clock = clock;
        this.returnWindow = returnWindow;
    }

    /** 클레임 대상 — storeShippingFeeIfLast 는 그 스토어 마지막 상품주문일 때만 스토어 배송비, 아니면 0 */
    public record ClaimTarget(Order order, OrderItem item, long cashLeft, long storeShippingFeeIfLast) {
    }

    /** 미리보기 · 접수 전 확인 — 잠그지 않는다. 신청할 수 없으면 예외 */
    @Transactional(readOnly = true)
    public ClaimTarget find(Long buyerId, String orderNo, Long orderItemId, ClaimType type) {
        return check(load(orderRepository.findByOrderNoAndBuyerId(orderNo, buyerId), orderItemId), type);
    }

    /**
     * 접수 — 주문 행을 잠가 같은 주문의 클레임이 동시에 와도 남은 결제 금액 · 스토어 마지막 여부를 차례로 계산한다.
     * 호출하는 쪽 트랜잭션 안에서만 (잠금이 그 트랜잭션 끝까지 간다).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ClaimTarget lock(Long buyerId, String orderNo, Long orderItemId, ClaimType type) {
        return check(load(orderRepository.findMineForUpdate(orderNo, buyerId), orderItemId), type);
    }

    /**
     * 반품 · 교환 신청 (CLM-03 · 04) — 클레임 REQUESTED · 상품주문 RETURN_REQUESTED / EXCHANGE_REQUESTED · 이력.
     * 반품은 환불 예정액(반품 배송비 차감)을 클레임에 남긴다. 교환은 결제 · 재고 변동이 없어 금액 없음.
     * 판매자 승인 · 수거 · 환불 · 재배송은 판매자 클레임 처리(CLM-05).
     */
    @Transactional
    public ClaimResponse openReturnOrExchange(Long buyerId, String orderNo, ClaimCreateRequest request,
                                              long returnShippingFee) {
        ClaimTarget target = lock(buyerId, orderNo, request.orderItemId(), request.type());
        OrderItem item = target.item();
        Refund refund = request.type() == ClaimType.RETURN
                ? ClaimRefundPolicy.ret(item.getItemAmt(), item.getUsedPoint(), returnShippingFee, target.cashLeft())
                : null;
        OrderClaim claim = claimRepository.save(OrderClaim.builder().orderItem(item).requesterId(buyerId)
                .claimType(request.type()).reasonCode(request.reasonCode()).reasonDetail(request.reasonDetail())
                .refundAmt(refund == null ? null : refund.refundAmt())
                .restorePoint(refund == null ? null : refund.restorePoint())
                .requestedAt(LocalDateTime.now(clock)).build());
        if (request.type() == ClaimType.RETURN) {
            item.requestReturn();
        } else {
            item.requestExchange();
        }
        historyRepository.save(OrderItemHistory.of(item, ActorType.BUYER, buyerId, OrderItemStatus.DELIVERED,
                item.getStatus(), request.type() == ClaimType.RETURN ? "구매자 반품 신청" : "구매자 교환 신청"));
        return new ClaimResponse(claim.getId(), item.getId(), request.type(), ClaimStatus.REQUESTED,
                refund == null ? 0L : refund.refundAmt(), refund == null ? 0L : refund.restorePoint());
    }

    private ClaimTarget load(Optional<Order> found, Long orderItemId) {
        Order order = found.orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));
        List<OrderItem> items = orderItemRepository.findByOrderIdOrderByIdAsc(order.getId());
        OrderItem item = items.stream().filter(i -> i.getId().equals(orderItemId)).findFirst()
                .orElseThrow(() -> new ClaimException(ClaimErrorCode.CLAIM_ITEM_NOT_FOUND));
        long cashLeft = order.getPayAmt() - claimRepository.sumRefundAmt(order.getId(), REFUNDING);
        long shippingFee = lastOfStore(items, item) ? storeShippingFee(order, item) : 0L;
        return new ClaimTarget(order, item, cashLeft, shippingFee);
    }

    /** 한 상품주문에 진행 중 클레임 1개 · 취소는 결제완료에서만 · 반품 · 교환은 배송완료 후 반품 기간 안에서만 */
    private ClaimTarget check(ClaimTarget target, ClaimType type) {
        OrderItem item = target.item();
        if (claimRepository.existsByOrderItemIdAndStatusIn(item.getId(), OPEN)) {
            throw new ClaimException(ClaimErrorCode.CLAIM_IN_PROGRESS);
        }
        if (type == ClaimType.CANCEL) {
            if (target.order().getStatus() != OrderStatus.PAID || item.getStatus() != OrderItemStatus.PAYMENT_DONE) {
                throw new ClaimException(ClaimErrorCode.CLAIM_NOT_CANCELLABLE);   // 배송준비 취소 요청(CLM-02)은 별도
            }
        } else if (item.getStatus() != OrderItemStatus.DELIVERED || item.getDeliveredAt() == null
                || LocalDateTime.now(clock).isAfter(item.getDeliveredAt().plus(returnWindow))) {
            throw new ClaimException(ClaimErrorCode.CLAIM_NOT_RETURNABLE);
        }
        return target;
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
}
