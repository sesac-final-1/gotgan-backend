package com.sesac.gojigi.order.claim.service;

import com.sesac.gojigi.order.claim.dto.request.ClaimCreateRequest;
import com.sesac.gojigi.order.claim.dto.response.ClaimPreviewResponse;
import com.sesac.gojigi.order.claim.dto.response.ClaimResponse;
import com.sesac.gojigi.order.claim.entity.ClaimReasonCode;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import com.sesac.gojigi.order.claim.service.ClaimRefundPolicy.Refund;
import com.sesac.gojigi.order.claim.service.ClaimTxService.ClaimTarget;
import com.sesac.gojigi.order.order.client.ShippingPolicyClient;
import com.sesac.gojigi.order.order.entity.OrderItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 반품 · 교환 신청 (CLM-03 · 04) · 클레임 환불 예정액 — 스토어 배송비 정책(store)을 트랜잭션 밖에서 읽고 ClaimTxService 에 넘긴다.
 * 취소(CLM-01)는 Saga 라 CancelOrderItemSaga 가 맡는다.
 */
@Service
@RequiredArgsConstructor
public class ClaimService {

    private final ClaimTxService claimTx;
    private final ShippingPolicyClient shippingPolicy;

    /** 환불 예정액 — 신청과 같은 조건으로 계산 (같은 주문의 다른 클레임이 끼면 실제 접수 금액은 달라질 수 있음) */
    public ClaimPreviewResponse preview(Long buyerId, String orderNo, Long orderItemId, ClaimType type,
                                        ClaimReasonCode reasonCode) {
        ClaimTarget target = claimTx.find(buyerId, orderNo, orderItemId, type);
        OrderItem item = target.item();
        Refund refund = switch (type) {
            case CANCEL -> ClaimRefundPolicy.cancel(item.getItemAmt(), item.getUsedPoint(),
                    target.storeShippingFeeIfLast(), target.cashLeft());
            case RETURN -> ClaimRefundPolicy.ret(item.getItemAmt(), item.getUsedPoint(),
                    returnShippingFee(item, reasonCode), target.cashLeft());
            case EXCHANGE -> new Refund(0L, 0L, 0L);   // 같은 옵션 재배송 — 결제 · 재고 변동 없음, 교환 배송비 받지 않음
        };
        return new ClaimPreviewResponse(item.getId(), type, reasonCode, item.getItemAmt(), refund.shippingFee(),
                ClaimRefundPolicy.sellerFault(reasonCode), refund.refundAmt(), refund.restorePoint(),
                type == ClaimType.EXCHANGE ? 0L : item.getEarnedPoint());
    }

    /** 반품 · 교환 신청 — 먼저 신청 가능 여부를 확인하고 스토어 배송비를 읽은 뒤, 주문을 잠근 트랜잭션에서 다시 확인하고 접수 */
    public ClaimResponse requestReturnOrExchange(Long buyerId, String orderNo, ClaimCreateRequest request) {
        ClaimTarget target = claimTx.find(buyerId, orderNo, request.orderItemId(), request.type());
        long fee = request.type() == ClaimType.RETURN ? returnShippingFee(target.item(), request.reasonCode()) : 0L;
        return claimTx.openReturnOrExchange(buyerId, orderNo, request, fee);
    }

    private long returnShippingFee(OrderItem item, ClaimReasonCode reasonCode) {
        if (ClaimRefundPolicy.sellerFault(reasonCode)) {
            return 0L;   // 판매자 귀책이면 스토어 정책을 부를 필요도 없다
        }
        return ClaimRefundPolicy.returnShippingFee(reasonCode,
                shippingPolicy.getShippingPolicy(item.getStoreId()).baseFee());
    }
}
