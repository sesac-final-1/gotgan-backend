package com.sesac.gojigi.user.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.user.common.event.EventEnvelope;
import com.sesac.gojigi.user.user.dto.event.OrderItemConfirmedPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/** order 가 보내는 OrderItemConfirmed 봉투(docs/events.md 전체 필드)를 읽는지 — 정산 · 리뷰용 필드는 무시 */
class OrderItemConfirmedListenerTest {

    private static final String CONFIRMED = """
            {"eventId":"0b6f3c1e-3333-4a2b-9c3d-000000000001","eventType":"OrderItemConfirmed",
             "occurredAt":"2026-10-27T10:00:00+09:00","aggregateId":"20261027-000001-01","version":1,
             "payload":{"orderItemId":11,"orderNo":"20261027-000001","buyerId":4,"storeId":1,"productId":77,
                        "productName":"린넨 셔츠","categoryId":11,"topCategoryId":1,"itemAmt":20000,"earnedPoint":900,
                        "confirmedAt":"2026-10-27T10:00:00+09:00"}}
            """;

    private final PurchaseConfirmedService service = mock(PurchaseConfirmedService.class);
    private final OrderItemConfirmedListener listener = new OrderItemConfirmedListener(JsonMapper.builder().build(), service);

    @Test
    @DisplayName("OrderItemConfirmed 봉투를 읽어 회원 · 상품주문 금액 · 적립 포인트로 구매확정 반영을 부른다")
    @SuppressWarnings("unchecked")
    void onMessage_parsesEnvelope() {
        ArgumentCaptor<EventEnvelope<OrderItemConfirmedPayload>> captor = ArgumentCaptor.forClass(EventEnvelope.class);

        listener.onMessage(CONFIRMED);

        verify(service).apply(captor.capture());
        assertThat(listener.eventType()).isEqualTo("OrderItemConfirmed");
        assertThat(captor.getValue().payload())
                .isEqualTo(new OrderItemConfirmedPayload(11L, "20261027-000001", 4L, 20_000L, 900L));
    }
}
