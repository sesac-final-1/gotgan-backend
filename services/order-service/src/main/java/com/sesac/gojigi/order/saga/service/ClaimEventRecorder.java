package com.sesac.gojigi.order.saga.service;

import com.sesac.gojigi.order.claim.dto.event.OrderItemCancelledPayload;
import com.sesac.gojigi.order.claim.dto.event.OrderItemReturnedPayload;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import com.sesac.gojigi.order.claim.entity.OrderClaim;
import com.sesac.gojigi.order.claim.repository.OrderClaimRepository;
import com.sesac.gojigi.order.common.event.EventOutboxService;
import com.sesac.gojigi.order.common.event.OutboxEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * OrderItemCancelled · OrderItemReturned 발행 — 취소 · 반품 환불 Saga 가 끝나는 트랜잭션(CancelItemTxService#finish) 안에서 아웃박스에 남긴다.
 * 소비자: 알림 Lambda(취소 · 반품 완료 메일). 포인트 환원 · 회수는 Saga 가 user 를 직접 불러 이미 끝났다 (이벤트는 통보용).
 */
@Component
@RequiredArgsConstructor
public class ClaimEventRecorder {

    private final OrderClaimRepository claimRepository;
    private final EventOutboxService outbox;

    @EventListener
    public void onFinished(CancelItemTxService.ClaimFinished finished) {
        OrderClaim claim = claimRepository.findById(finished.claimId()).orElseThrow();
        String itemNo = claim.getOrderItem().getItemNo();
        if (claim.getClaimType() == ClaimType.RETURN) {
            outbox.enqueue("OrderItemReturned", OutboxEvent.AggregateType.ORDER_ITEM, itemNo,
                    OrderItemReturnedPayload.from(claim));
        } else {
            outbox.enqueue("OrderItemCancelled", OutboxEvent.AggregateType.ORDER_ITEM, itemNo,
                    OrderItemCancelledPayload.from(claim));
        }
    }
}
