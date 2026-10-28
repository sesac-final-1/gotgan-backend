package com.sesac.gojigi.store.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import software.amazon.awssdk.services.sns.model.SnsException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class EventOutboxServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 14, 30);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_ATTEMPTS = 3;

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final SnsEventPublisher sns = mock(SnsEventPublisher.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private EventOutboxService service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        given(txManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        service = new EventOutboxService(repository, sns, eventPublisher, JSON, txManager,
                Clock.fixed(NOW.atZone(KST).toInstant(), KST), Duration.ofSeconds(10), MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("enqueue 는 부르는 쪽이 정한 eventId · occurredAt 으로 NEW 행을 남기고 커밋 후 발행 신호를 던진다")
    void enqueue_whenCalled_savesNewRowAndSignals() {
        // given
        given(repository.save(any(OutboxEvent.class))).willAnswer(inv -> withId(inv.getArgument(0), 7L));

        // when
        service.enqueue("StoreApproved", "3", "evt-1", NOW, Map.of("storeId", 3));

        // then
        ArgumentCaptor<OutboxEvent> row = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(row.capture());
        assertThat(row.getValue().getEventId()).isEqualTo("evt-1");
        assertThat(row.getValue().getEventType()).isEqualTo("StoreApproved");
        assertThat(row.getValue().getAggregateId()).isEqualTo("3");
        assertThat(row.getValue().getPayload()).isEqualTo("{\"storeId\":3}");
        assertThat(row.getValue().getOccurredAt()).isEqualTo(NOW);
        assertThat(row.getValue().getStatus()).isEqualTo(OutboxEvent.Status.NEW);
        verify(eventPublisher).publishEvent(new EventOutboxService.Enqueued(7L));
    }

    @Test
    @DisplayName("발행하면 봉투(eventId · eventType · occurredAt +09:00 · aggregateId · version 1 · payload)를 보내고 SENT 로 바꾼다")
    void send_whenSnsSucceeds_sendsEnvelopeAndMarksSent() {
        // given
        OutboxEvent row = row(7L, NOW.minusMinutes(1));
        given(repository.findById(7L)).willReturn(Optional.of(row));

        // when
        service.send(7L);

        // then
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(sns).send(eq("StoreApproved"), eq("3"), eq("evt-1"), json.capture());
        JsonNode envelope = JSON.readTree(json.getValue());
        assertThat(envelope.get("eventId").asString()).isEqualTo("evt-1");
        assertThat(envelope.get("eventType").asString()).isEqualTo("StoreApproved");
        assertThat(envelope.get("occurredAt").asString()).isEqualTo("2026-10-28T14:29:00+09:00");
        assertThat(envelope.get("aggregateId").asString()).isEqualTo("3");
        assertThat(envelope.get("version").asInt()).isEqualTo(1);
        assertThat(envelope.get("payload").get("storeId").asLong()).isEqualTo(3L);
        assertThat(row.getStatus()).isEqualTo(OutboxEvent.Status.SENT);
        assertThat(row.getSentAt()).isEqualTo(NOW);
        assertThat(row.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 보낸 행은 다시 보내지 않는다 — 커밋 직후 발행과 발행기가 겹쳐도 한 번")
    void send_whenAlreadySent_skips() {
        // given
        OutboxEvent row = row(7L, NOW);
        row.markSent(NOW);
        given(repository.findById(7L)).willReturn(Optional.of(row));

        // when
        service.send(7L);

        // then
        verify(sns, never()).send(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("발행이 실패하면 NEW 로 남기고 시도 횟수 · 실패 사유를 기록한다 — 한도 전에는 warn")
    void send_whenSnsFails_recordsAttemptAndKeepsNew(CapturedOutput output) {
        // given
        OutboxEvent row = row(7L, NOW);
        given(repository.findById(7L)).willReturn(Optional.of(row));
        willThrow(SnsException.builder().message("timeout").build())
                .given(sns).send(anyString(), anyString(), anyString(), anyString());

        // when
        service.send(7L);

        // then
        assertThat(row.getStatus()).isEqualTo(OutboxEvent.Status.NEW);
        assertThat(row.getAttemptCount()).isEqualTo(1);
        assertThat(row.getLastError()).contains("timeout");
        assertThat(output).contains("발행기가 다시 보냄").doesNotContain("수동 확인 필요");
    }

    @Test
    @DisplayName("한도에 닿은 실패만 error + 스택, 그 뒤 실패는 한 줄 warn — 반복 실패는 첫 회만 스택 (컨벤션 2-8)")
    void send_whenFailsPastLimit_logsErrorOnce(CapturedOutput output) {
        // given
        OutboxEvent row = row(7L, NOW);
        given(repository.findById(7L)).willReturn(Optional.of(row));
        willThrow(SnsException.builder().message("timeout").build())
                .given(sns).send(anyString(), anyString(), anyString(), anyString());

        // when
        for (int i = 0; i < MAX_ATTEMPTS + 2; i++) {
            service.send(7L);
        }

        // then
        assertThat(row.getAttemptCount()).isEqualTo(MAX_ATTEMPTS + 2);
        assertThat(count(output.getOut(), "이벤트 발행이 3회 실패 — 수동 확인 필요")).isEqualTo(1);
        assertThat(count(output.getOut(), "이벤트 발행 계속 실패")).isEqualTo(2);
        assertThat(count(output.getOut(), "\tat ")).as("스택 트레이스는 error 한 번에만").isPositive();
        assertThat(output.getOut().substring(output.getOut().indexOf("이벤트 발행 계속 실패"))).doesNotContain("\tat ");
    }

    @Test
    @DisplayName("발행기는 retry-after(10초)보다 오래된 NEW 를 50건까지 오래된 순으로 보낸다")
    void publishPending_whenRowsLeft_sendsEach() {
        // given
        OutboxEvent first = row(7L, NOW.minusMinutes(2));
        OutboxEvent second = row(8L, NOW.minusMinutes(1));
        given(repository.findByStatusAndOccurredAtBeforeOrderByIdAsc(OutboxEvent.Status.NEW, NOW.minusSeconds(10),
                Limit.of(50))).willReturn(List.of(first, second));
        given(repository.findById(7L)).willReturn(Optional.of(first));
        given(repository.findById(8L)).willReturn(Optional.of(second));

        // when
        service.publishPending();

        // then
        assertThat(first.isSent()).isTrue();
        assertThat(second.isSent()).isTrue();
    }

    @Test
    @DisplayName("실패 사유는 200자까지만 남긴다 (last_error VARCHAR(200))")
    void markFailed_whenErrorTooLong_truncates() {
        // given
        OutboxEvent row = row(7L, NOW);

        // when
        row.markFailed("x".repeat(300));

        // then
        assertThat(row.getLastError()).hasSize(200);
    }

    private static int count(String text, String word) {
        return text.split(java.util.regex.Pattern.quote(word), -1).length - 1;
    }

    private static OutboxEvent row(Long id, LocalDateTime occurredAt) {
        return withId(OutboxEvent.of("evt-1", "StoreApproved", "3", "{\"storeId\":3}", occurredAt), id);
    }

    private static OutboxEvent withId(OutboxEvent row, Long id) {
        ReflectionTestUtils.setField(row, "id", id);
        return row;
    }
}
