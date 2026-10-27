package com.sesac.gojigi.order.claim.dto.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.order.claim.entity.ClaimReasonCode;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import com.sesac.gojigi.order.claim.entity.OrderClaim;
import com.sesac.gojigi.order.order.entity.OrderFixtures;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 취소 · 반품 이벤트 payload — 환불액은 클레임, 환원 · 회수 포인트는 상품주문의 사용 · 적립 포인트 */
class ClaimEventPayloadTest {

    private OrderClaim claim(ClaimType type) {
        return OrderClaim.builder()
                .orderItem(OrderFixtures.item(OrderFixtures.order())).requesterId(4L).claimType(type)
                .reasonCode(ClaimReasonCode.CHANGE_OF_MIND).refundAmt(62_000L)
                .requestedAt(LocalDateTime.of(2026, 10, 22, 10, 0))
                .build();
    }

    @Test
    @DisplayName("OrderItemCancelled — 환불액 · 돌려준 사용 포인트 · 회수한 적립 포인트를 담는다")
    void orderItemCancelled_from_mapsClaimAndItem() {
        // when
        OrderItemCancelledPayload payload = OrderItemCancelledPayload.from(claim(ClaimType.CANCEL));

        // then
        assertThat(payload).isEqualTo(new OrderItemCancelledPayload(null, "20261022-000123", 4L, "buyer@example.com",
                "김구매", "제철 사과 3kg", "특", 62_000L, 2_000L, 0L));   // 구매확정 전 — 회수할 적립 없음
    }

    @Test
    @DisplayName("OrderItemReturned — 취소와 같은 필드를 담는다")
    void orderItemReturned_from_mapsClaimAndItem() {
        // when
        OrderItemReturnedPayload payload = OrderItemReturnedPayload.from(claim(ClaimType.RETURN));

        // then
        assertThat(payload).isEqualTo(new OrderItemReturnedPayload(null, "20261022-000123", 4L, "buyer@example.com",
                "김구매", "제철 사과 3kg", "특", 62_000L, 2_000L, 0L));   // 구매확정 전 — 회수할 적립 없음
    }
}
