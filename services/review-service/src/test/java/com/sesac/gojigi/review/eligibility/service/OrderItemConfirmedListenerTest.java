package com.sesac.gojigi.review.eligibility.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.review.common.event.EventEnvelope;
import com.sesac.gojigi.review.eligibility.dto.event.OrderItemConfirmedPayload;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/** order 가 보내는 OrderItemConfirmed 봉투(docs/events.md 전체 필드)를 읽는지 — 금액 · 카테고리 · 적립 포인트는 무시 */
class OrderItemConfirmedListenerTest {

    private static final String CONFIRMED = """
            {"eventId":"0b6f3c1e-3333-4a2b-9c3d-000000000001","eventType":"OrderItemConfirmed",
             "occurredAt":"2026-10-27T10:00:01+09:00","aggregateId":"20261027-000001-01","version":1,
             "payload":{"orderItemId":11,"orderNo":"20261027-000001","buyerId":4,"storeId":1,"productId":77,
                        "productName":"린넨 셔츠","categoryId":11,"topCategoryId":1,"itemAmt":20000,"earnedPoint":900,
                        "confirmedAt":"2026-10-27T10:00:00+09:00","buyerDisplayName":"곳간지기",
                        "optionName":"오트밀 / M","thumbnailUrl":"https://cdn.gotgan.click/products/1/a1b2.jpg"}}
            """;

    private final ReviewEligibilityService service = mock(ReviewEligibilityService.class);
    private final OrderItemConfirmedListener listener = new OrderItemConfirmedListener(JsonMapper.builder().build(), service);

    @Test
    @DisplayName("OrderItemConfirmed 봉투를 읽어 상품주문 · 구매자 · 상품 스냅샷으로 자격 저장을 부른다")
    @SuppressWarnings("unchecked")
    void onMessage_parsesEnvelope() {
        ArgumentCaptor<EventEnvelope<OrderItemConfirmedPayload>> captor = ArgumentCaptor.forClass(EventEnvelope.class);

        listener.onMessage(CONFIRMED);

        verify(service).grant(captor.capture());
        assertThat(listener.eventType()).isEqualTo("OrderItemConfirmed");
        assertThat(captor.getValue().eventId()).isEqualTo("0b6f3c1e-3333-4a2b-9c3d-000000000001");
        OrderItemConfirmedPayload payload = captor.getValue().payload();
        // Jackson 은 오프셋을 UTC 로 맞춰 읽는다 — 같은 순간인지만 본다 (저장 때 KST 로 바꿈)
        assertThat(payload).usingRecursiveComparison().ignoringFields("confirmedAt")
                .isEqualTo(new OrderItemConfirmedPayload(11L, "20261027-000001", 4L, 1L, 77L, "린넨 셔츠", null, "곳간지기",
                        "오트밀 / M", "https://cdn.gotgan.click/products/1/a1b2.jpg"));
        assertThat(payload.confirmedAt()).isAtSameInstantAs(OffsetDateTime.parse("2026-10-27T10:00:00+09:00"));
    }
}
