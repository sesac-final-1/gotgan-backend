package com.sesac.gojigi.order.saga.service;

import com.sesac.gojigi.order.claim.entity.ClaimReasonCode;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import com.sesac.gojigi.order.claim.exception.ClaimErrorCode;
import com.sesac.gojigi.order.claim.exception.ClaimException;
import com.sesac.gojigi.order.claim.entity.OrderClaim;
import com.sesac.gojigi.order.claim.repository.OrderClaimRepository;
import com.sesac.gojigi.order.claim.service.ClaimRefundPolicy;
import com.sesac.gojigi.order.claim.service.ClaimRefundPolicy.Refund;
import com.sesac.gojigi.order.claim.service.ClaimTxService;
import com.sesac.gojigi.order.claim.service.ClaimTxService.ClaimTarget;
import com.sesac.gojigi.order.order.entity.ActorType;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderItemHistory;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import com.sesac.gojigi.order.order.repository.OrderItemHistoryRepository;
import com.sesac.gojigi.order.order.repository.OrderItemRepository;
import com.sesac.gojigi.order.saga.entity.SagaInstance;
import com.sesac.gojigi.order.saga.entity.SagaType;
import com.sesac.gojigi.order.saga.repository.SagaInstanceRepository;
import java.time.Clock;
import java.time.LocalDateTime;
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

    private final ClaimTxService claimTx;
    private final OrderItemRepository orderItemRepository;
    private final OrderItemHistoryRepository historyRepository;
    private final OrderClaimRepository claimRepository;
    private final SagaInstanceRepository sagaInstanceRepository;
    private final Clock clock;

    /** 취소 Saga 가 단계마다 쓰는 값 — 클레임을 만들 때 정해 두고 재시도해도 같은 값 */
    public record CancelContext(Long sagaInstanceId, String sagaId, Long claimId, ClaimType type, Long orderItemId,
                                String orderNo, Long buyerId, Long productOptionId, int quantity, long refundAmt,
                                long restorePoint, long earnedPoint) {
    }

    /**
     * 취소 접수 — 클레임(자동 승인) · 상품주문 CANCEL_REQUESTED · 이력 · 취소 Saga 를 한 트랜잭션으로.
     * 환불은 ClaimRefundPolicy.cancel — 스토어 마지막 상품주문이면 배송비까지, 남은 결제 금액을 넘는 몫은 포인트로.
     * ClaimTxService.lock 이 주문 행을 잠가 같은 주문의 취소가 동시에 와도 남은 금액 · 스토어 마지막 여부를 차례로 계산한다.
     */
    @Transactional
    public CancelContext open(Long buyerId, String orderNo, Long orderItemId, ClaimReasonCode reasonCode,
                              String reasonDetail) {
        ClaimTarget target = claimTx.lock(buyerId, orderNo, orderItemId, ClaimType.CANCEL);
        Order order = target.order();
        OrderItem item = target.item();
        Refund refund = ClaimRefundPolicy.cancel(item.getItemAmt(), item.getUsedPoint(),
                target.storeShippingFeeIfLast(), target.cashLeft());

        LocalDateTime now = LocalDateTime.now(clock);
        OrderClaim claim = OrderClaim.builder().orderItem(item).requesterId(buyerId).claimType(ClaimType.CANCEL)
                .reasonCode(reasonCode).reasonDetail(reasonDetail).refundAmt(refund.refundAmt())
                .restorePoint(refund.restorePoint()).requestedAt(now).build();
        claim.approve(now);   // 결제완료 단계 취소는 판매자 승인 없이 즉시
        claimRepository.save(claim);
        item.requestCancel();
        historyRepository.save(OrderItemHistory.of(item, ActorType.BUYER, buyerId, OrderItemStatus.PAYMENT_DONE,
                OrderItemStatus.CANCEL_REQUESTED, "구매자 즉시 취소"));
        SagaInstance saga = sagaInstanceRepository.save(SagaInstance.forClaim(claim, UUID.randomUUID().toString(),
                SagaType.CANCEL_ITEM));
        return context(saga, claim, item, order);
    }

    /**
     * 반품 수거 완료 (CLM-03 · 판매자) — 클레임 COLLECTED · 반품 환불 Saga 를 한 트랜잭션으로. 환불액 · 돌려줄 포인트는 신청 때 정한 값.
     * 내 스토어 상품주문의 반품만, 승인(수거 요청) 상태에서만.
     */
    @Transactional
    public CancelContext collectReturn(Long storeId, Long claimId) {
        OrderClaim claim = claimRepository.findById(claimId)
                .filter(c -> c.getOrderItem().getStoreId().equals(storeId))
                .orElseThrow(() -> new ClaimException(ClaimErrorCode.CLAIM_NOT_FOUND));
        if (claim.getClaimType() != ClaimType.RETURN) {
            throw new ClaimException(ClaimErrorCode.CLAIM_INVALID_STATUS);
        }
        claim.collect(LocalDateTime.now(clock));
        SagaInstance saga = sagaInstanceRepository.save(SagaInstance.forClaim(claim, UUID.randomUUID().toString(),
                SagaType.RETURN_ITEM));
        return context(saga, claim, claim.getOrderItem(), claim.getOrderItem().getOrder());
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
     * 취소 · 반품 완료 — 상품주문 CANCELLED / RETURNED · 이력 · 클레임 COMPLETED · (모든 상품주문이 취소되면) 주문 CANCELLED · Saga 완료.
     * TODO(Outbox · ORD-11): OrderItemCancelled · OrderItemReturned 이벤트를 같은 트랜잭션에 기록 (refundAmt · restoredPoint · revokedPoint)
     */
    @Transactional
    public void finish(Long sagaInstanceId) {
        SagaInstance saga = sagaInstanceRepository.findById(sagaInstanceId)
                .orElseThrow(() -> new IllegalStateException("saga_instance 없음 - id=" + sagaInstanceId));
        OrderClaim claim = saga.getOrderClaim();
        OrderItem item = claim.getOrderItem();
        LocalDateTime now = LocalDateTime.now(clock);
        boolean ret = claim.getClaimType() == ClaimType.RETURN;
        OrderItemStatus from = item.getStatus();
        if (ret) {
            item.returned();
        } else {
            item.cancelled();
        }
        historyRepository.save(OrderItemHistory.of(item, ActorType.SYSTEM, null, from, item.getStatus(),
                (ret ? "반품 완료" : "취소 완료") + " — 환불 " + claim.getRefundAmt() + "원 · 포인트 " + claim.getRestorePoint()));
        claim.complete(now);
        Order order = saga.getOrder();
        boolean allCancelled = orderItemRepository.findByOrderIdOrderByIdAsc(order.getId()).stream()
                .allMatch(i -> i.getStatus() == OrderItemStatus.CANCELLED);
        if (allCancelled) {
            order.markCancelled();
        }
        saga.complete();
    }

    private static CancelContext context(SagaInstance saga, OrderClaim claim, OrderItem item, Order order) {
        return new CancelContext(saga.getId(), saga.getSagaId(), claim.getId(), claim.getClaimType(), item.getId(),
                order.getOrderNo(),
                order.getBuyerId(), item.getProductOptionId(), item.getQuantity(), claim.getRefundAmt(),
                claim.getRestorePoint(), item.getEarnedPoint());
    }
}
