package com.sesac.gojigi.user.point.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.user.common.event.EventEnvelope;
import com.sesac.gojigi.user.point.dto.event.OrderPaidPayload;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/** order 가 보내는 OrderPaid 봉투(docs/events.md 전체 필드)를 읽는지 — 메일용 필드는 무시 */
class OrderPaidListenerTest {

    private static final String ORDER_PAID = """
            {"eventId":"0b6f3c1e-2222-4a2b-9c3d-000000000001","eventType":"OrderPaid",
             "occurredAt":"2026-10-27T10:00:00+09:00","aggregateId":"20261027-000001","version":1,
             "payload":{"orderNo":"20261027-000001","buyerId":4,"ordererEmail":"wlsgksvheh+buyer1@gmail.com",
                        "ordererName":"구매자1","payAmt":28000,"usedPoint":2000,
                        "items":[{"orderItemId":11,"earnedPoint":1000,"productName":"린넨 셔츠","optionName":"L",
                                  "quantity":1,"itemAmt":20000},
                                 {"orderItemId":12,"earnedPoint":400,"productName":"양말","optionName":null,
                                  "quantity":2,"itemAmt":8000}]}}
            """;

    private final PointService pointService = mock(PointService.class);
    private final OrderPaidListener listener = new OrderPaidListener(JsonMapper.builder().build(), pointService);

    @Test
    @DisplayName("OrderPaid 봉투를 읽어 회원 · 주문번호 · 상품주문별 적립 포인트로 적립을 부른다")
    @SuppressWarnings("unchecked")
    void onMessage_parsesEnvelope() {
        ArgumentCaptor<EventEnvelope<OrderPaidPayload>> captor = ArgumentCaptor.forClass(EventEnvelope.class);

        listener.onMessage(ORDER_PAID);

        verify(pointService).earn(captor.capture());
        EventEnvelope<OrderPaidPayload> event = captor.getValue();
        assertThat(listener.eventType()).isEqualTo("OrderPaid");
        assertThat(event.eventId()).isEqualTo("0b6f3c1e-2222-4a2b-9c3d-000000000001");
        assertThat(event.payload()).isEqualTo(new OrderPaidPayload("20261027-000001", 4L, List.of(
                new OrderPaidPayload.Item(11L, 1000L), new OrderPaidPayload.Item(12L, 400L))));
    }
}
