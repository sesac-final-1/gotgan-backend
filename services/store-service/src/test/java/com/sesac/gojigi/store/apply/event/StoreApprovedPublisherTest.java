package com.sesac.gojigi.store.apply.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.store.apply.dto.event.StoreApprovedPayload;
import com.sesac.gojigi.store.common.event.SnsEventPublisher;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sns.model.SnsException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class StoreApprovedPublisherTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final StoreApprovedEvent EVENT = new StoreApprovedEvent(27L, LocalDateTime.of(2026, 10, 28, 14, 30),
            new StoreApprovedPayload(3L, 4L, "곳간상회", "seller@example.com",
                    new StoreApprovedPayload.SettlementAccount("국민", "000000-00-000000", "홍길동")));

    private final SnsEventPublisher sns = mock(SnsEventPublisher.class);
    private final StoreApprovedPublisher publisher = new StoreApprovedPublisher(sns, JSON);

    @Test
    @DisplayName("봉투는 eventType StoreApproved · aggregateId = storeId · occurredAt = 승인 시각(+09:00) · events.md payload 이름 그대로")
    void on_whenApproved_sendsEnvelope() {
        // when
        publisher.on(EVENT);

        // then
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(sns).send(eq("StoreApproved"), eq("3"), eq(StoreApprovedPublisher.eventId(27L)), json.capture());
        JsonNode envelope = JSON.readTree(json.getValue());
        assertThat(envelope.get("eventId").asString()).isEqualTo(StoreApprovedPublisher.eventId(27L));
        assertThat(envelope.get("eventType").asString()).isEqualTo("StoreApproved");
        assertThat(envelope.get("occurredAt").asString()).isEqualTo("2026-10-28T14:30:00+09:00");
        assertThat(envelope.get("aggregateId").asString()).isEqualTo("3");
        assertThat(envelope.get("version").asInt()).isEqualTo(1);
        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("storeId").asLong()).isEqualTo(3L);
        assertThat(payload.get("userId").asLong()).isEqualTo(4L);
        assertThat(payload.get("storeName").asString()).isEqualTo("곳간상회");
        assertThat(payload.get("sellerEmail").asString()).isEqualTo("seller@example.com");
        assertThat(payload.get("settlementAccount").get("bank").asString()).isEqualTo("국민");
        assertThat(payload.get("settlementAccount").get("number").asString()).isEqualTo("000000-00-000000");
        assertThat(payload.get("settlementAccount").get("holder").asString()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("eventId 는 신청 ID 로 정해지는 UUID — 같은 신청은 항상 같은 값, 다른 신청은 다른 값")
    void eventId_whenSameApplication_isStable() {
        // when · then
        assertThat(StoreApprovedPublisher.eventId(27L)).isEqualTo(StoreApprovedPublisher.eventId(27L))
                .isNotEqualTo(StoreApprovedPublisher.eventId(28L));
        assertThat(UUID.fromString(StoreApprovedPublisher.eventId(27L)).version()).isEqualTo(3);
    }

    @Test
    @DisplayName("SNS 발행이 실패해도 예외를 밖으로 던지지 않는다 — 승인은 이미 커밋됨")
    void on_whenSnsFails_doesNotThrow() {
        // given
        willThrow(SnsException.builder().message("timeout").build())
                .given(sns).send(anyString(), anyString(), anyString(), anyString());

        // when · then
        assertThatCode(() -> publisher.on(EVENT)).doesNotThrowAnyException();
    }
}
