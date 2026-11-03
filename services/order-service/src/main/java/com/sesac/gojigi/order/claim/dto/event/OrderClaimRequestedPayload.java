package com.sesac.gojigi.order.claim.dto.event;

import com.sesac.gojigi.order.claim.entity.ClaimReasonCode;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import com.sesac.gojigi.order.claim.entity.OrderClaim;
import com.sesac.gojigi.order.order.entity.OrderItem;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * OrderClaimRequested payload (docs/events.md) — admin 알림 센터(판매자에게 CLAIM_REQUESTED 화면 알림).
 * 구매자의 반품 · 교환 신청만 — 취소는 판매자 확인 없이 바로 끝나 OrderItemCancelled 로 알린다. 철회는 알리지 않는다.
 * requestedAt 은 Asia/Seoul 오프셋 ISO-8601.
 */
public record OrderClaimRequestedPayload(Long claimId, ClaimType claimType, Long orderItemId, String itemNo,
                                         String orderNo, Long storeId, String storeName, String productName,
                                         String optionName, ClaimReasonCode reasonCode, OffsetDateTime requestedAt) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static OrderClaimRequestedPayload from(OrderClaim claim) {
        OrderItem item = claim.getOrderItem();
        return new OrderClaimRequestedPayload(claim.getId(), claim.getClaimType(), item.getId(), item.getItemNo(),
                item.getOrder().getOrderNo(), item.getStoreId(), item.getStoreName(), item.getProductName(),
                item.getOptionName(), claim.getReasonCode(), claim.getRequestedAt().atZone(KST).toOffsetDateTime());
    }
}
