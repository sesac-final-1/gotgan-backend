package com.sesac.gojigi.user.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.user.common.event.EventEnvelope;
import com.sesac.gojigi.user.user.dto.event.StoreApprovedPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/** store 가 보낼 봉투 JSON 을 그대로 읽는지 — payload 의 나머지 필드(스토어명 · 이메일 · 정산 계좌)는 무시 */
class StoreApprovedListenerTest {

    /**
     * TODO(store 개발 완료 시): store 의 StoreApproved 발행이 아직 main 에 없어 docs/events.md 계약대로 만든 목 메시지.
     * 값은 시드 구매자3(user_id 6) · 스토어 3 — 발행이 들어오면 실제 메시지 모양과 맞춰 본다.
     */
    static final String MOCK_STORE_APPROVED = """
            {"eventId":"6c1f0a6e-0b0e-4c5b-9a8b-2f3c8e7d1a10","eventType":"StoreApproved",
             "occurredAt":"2026-10-23T10:00:00+09:00","aggregateId":"3","version":1,
             "payload":{"storeId":3,"userId":6,"storeName":"구매자3 상점","sellerEmail":"wlsgksvheh+buyer3@gmail.com",
                        "settlementAccount":{"bank":"기업은행","number":"010-0000-0006","holder":"구매자3"}}}
            """;

    private final SellerConversionService service = mock(SellerConversionService.class);
    private final StoreApprovedListener listener = new StoreApprovedListener(JsonMapper.builder().build(), service);

    @Test
    @DisplayName("StoreApproved 봉투를 읽어 eventId · storeId · userId 로 판매자 전환을 부른다")
    @SuppressWarnings("unchecked")
    void onMessage_parsesEnvelope() {
        ArgumentCaptor<EventEnvelope<StoreApprovedPayload>> captor = ArgumentCaptor.forClass(EventEnvelope.class);

        listener.onMessage(MOCK_STORE_APPROVED);

        verify(service).applyApproved(captor.capture());
        EventEnvelope<StoreApprovedPayload> event = captor.getValue();
        assertThat(listener.eventType()).isEqualTo("StoreApproved");
        assertThat(event.eventId()).isEqualTo("6c1f0a6e-0b0e-4c5b-9a8b-2f3c8e7d1a10");
        assertThat(event.eventType()).isEqualTo("StoreApproved");
        assertThat(event.payload()).isEqualTo(new StoreApprovedPayload(3L, 6L));
    }
}
