package com.sesac.gojigi.order.claim.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.order.claim.dto.response.ClaimDetailResponse;
import com.sesac.gojigi.order.claim.entity.ClaimStatus;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import com.sesac.gojigi.order.claim.entity.OrderClaim;
import com.sesac.gojigi.order.claim.exception.ClaimErrorCode;
import com.sesac.gojigi.order.claim.exception.ClaimException;
import com.sesac.gojigi.order.claim.repository.OrderClaimRepository;
import com.sesac.gojigi.order.order.entity.ActorType;
import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderItemHistory;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import com.sesac.gojigi.order.order.repository.OrderItemHistoryRepository;
import com.sesac.gojigi.order.order.service.DeliveryTxService;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 클레임 처리 (CLM-05) 로컬 트랜잭션 — 승인(수거 요청) · 거부 · 교환 수거 완료 · 교환 재배송 송장, 교환 재배송 배송완료.
 * 반품 수거 완료는 환불 Saga 라 CancelOrderItemSaga.collectReturn. 내 스토어 상품주문의 클레임만 — 아니면 같은 404.
 */
@Service
@RequiredArgsConstructor
public class SellerClaimTxService {

    private final OrderClaimRepository claimRepository;
    private final OrderItemHistoryRepository historyRepository;
    private final Clock clock;

    /** 승인 — 반품 · 교환 요청 → 수거 요청. 상품주문 상태는 반품요청 · 교환요청 그대로 */
    @Transactional
    public ClaimDetailResponse approve(Long storeId, Long claimId) {
        OrderClaim claim = mine(storeId, claimId);
        if (claim.getClaimType() == ClaimType.CANCEL) {
            throw new ClaimException(ClaimErrorCode.CLAIM_INVALID_STATUS);   // 결제완료 취소는 자동 승인, 배송준비 취소(CLM-02)는 아직 없음
        }
        claim.approve(LocalDateTime.now(clock));
        return ClaimDetailResponse.from(claim);
    }

    /** 거부 — 요청 상태에서만, 상품주문은 다시 배송완료(배송완료 시각 그대로) */
    @Transactional
    public ClaimDetailResponse reject(Long storeId, Long sellerId, Long claimId, String reason) {
        OrderClaim claim = mine(storeId, claimId);
        claim.reject(reason, LocalDateTime.now(clock));
        OrderItem item = claim.getOrderItem();
        OrderItemStatus from = item.getStatus();
        item.rejectClaim();
        historyRepository.save(OrderItemHistory.of(item, ActorType.SELLER, sellerId, from, item.getStatus(),
                "판매자 거부 — " + reason));
        return ClaimDetailResponse.from(claim);
    }

    /** 교환 수거 완료 — 이어서 재배송 송장 */
    @Transactional
    public ClaimDetailResponse collectExchange(Long storeId, Long claimId) {
        OrderClaim claim = mine(storeId, claimId);
        if (claim.getClaimType() != ClaimType.EXCHANGE) {
            throw new ClaimException(ClaimErrorCode.CLAIM_INVALID_STATUS);
        }
        claim.collect(LocalDateTime.now(clock));
        return ClaimDetailResponse.from(claim);
    }

    /**
     * 교환 재배송 송장 — 클레임 RESHIPPED, 상품주문의 택배사 · 송장 · 발송 시각을 재배송 것으로(송장번호를 비우면 mock).
     * 재배송의 배송완료는 판매자 배송완료 버튼 · 자동 배송완료가 처리하고(DeliveryTxService), 그때 onExchangeRedelivered 로 완료.
     */
    @Transactional
    public ClaimDetailResponse reship(Long storeId, Long sellerId, Long claimId, String courier, String trackingNo) {
        OrderClaim claim = mine(storeId, claimId);
        LocalDateTime now = LocalDateTime.now(clock);
        claim.reship(now);
        OrderItem item = claim.getOrderItem();
        String no = trackingNo == null || trackingNo.isBlank() ? DeliveryTxService.mockTrackingNo(courier, item.getId())
                : trackingNo;
        item.reshipExchange(courier, no, now);
        historyRepository.save(OrderItemHistory.of(item, ActorType.SELLER, sellerId, item.getStatus(), item.getStatus(),
                "교환 재배송 송장 " + courier + " " + no));
        return ClaimDetailResponse.from(claim);
    }

    /** 교환 재배송이 배송완료됨 — 배송완료 트랜잭션 안에서 교환 클레임 완료 (없으면 예외로 배송완료도 롤백) */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onExchangeRedelivered(DeliveryTxService.ExchangeRedelivered event) {
        claimRepository.findByOrderItemIdAndStatus(event.orderItemId(), ClaimStatus.RESHIPPED)
                .orElseThrow(() -> new ClaimException(ClaimErrorCode.CLAIM_INVALID_STATUS))
                .complete(LocalDateTime.now(clock));
    }

    private OrderClaim mine(Long storeId, Long claimId) {
        if (storeId == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "스토어 정보가 없습니다.");
        }
        return claimRepository.findById(claimId).filter(c -> c.getOrderItem().getStoreId().equals(storeId))
                .orElseThrow(() -> new ClaimException(ClaimErrorCode.CLAIM_NOT_FOUND));
    }
}
