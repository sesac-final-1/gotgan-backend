package com.sesac.gojigi.order.claim.dto.response;

import com.sesac.gojigi.order.claim.entity.ClaimReasonCode;
import com.sesac.gojigi.order.claim.entity.ClaimStatus;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import com.sesac.gojigi.order.claim.entity.OrderClaim;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import java.time.LocalDateTime;

/**
 * 클레임 1건 (내 클레임 내역 · 판매자 클레임 처리 CLM-05) — 상품은 주문 때 스냅샷.
 * refundAmt · restorePoint: 환불(예정)액 · 돌려줄 포인트, 교환이면 0. courier · trackingNo: 교환은 재배송 송장(등록 후).
 * buyerName 은 공개용 이름(닉네임 또는 가운데 마스킹 실명).
 */
public record ClaimDetailResponse(Long claimId, ClaimType type, ClaimStatus status, ClaimReasonCode reasonCode,
                                  String reasonDetail, String rejectReason, long refundAmt, long restorePoint,
                                  LocalDateTime requestedAt, LocalDateTime processedAt, Long orderItemId, String itemNo,
                                  String orderNo, String productName, String optionName, String thumbnailUrl,
                                  int quantity, long itemAmt, OrderItemStatus itemStatus, String buyerName,
                                  String courier, String trackingNo) {

    public static ClaimDetailResponse from(OrderClaim c) {
        OrderItem i = c.getOrderItem();
        Order o = i.getOrder();
        return new ClaimDetailResponse(c.getId(), c.getClaimType(), c.getStatus(), c.getReasonCode(),
                c.getReasonDetail(), c.getRejectReason(), c.getRefundAmt() == null ? 0L : c.getRefundAmt(),
                c.getRestorePoint() == null ? 0L : c.getRestorePoint(), c.getRequestedAt(), c.getProcessedAt(),
                i.getId(), i.getItemNo(), o.getOrderNo(), i.getProductName(), i.getOptionName(), i.getThumbnailUrl(),
                i.getQuantity(), i.getItemAmt(), i.getStatus(), o.getOrdererDisplayName(), i.getCourier(),
                i.getTrackingNo());
    }
}
