package com.sesac.gojigi.order.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.order.TestcontainersConfiguration;
import com.sesac.gojigi.order.common.config.SchedulingConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** O-16 — 한도만큼 실패한 행은 FAILED 로 빠져 발행기 조회를 막지 않는다 (MySQL 8.4, max-attempts 기본 10) */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, EventOutboxService.class, SchedulingConfig.class,
        EventOutboxFailedIntegrationTest.Beans.class })
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EventOutboxFailedIntegrationTest {

    @TestConfiguration
    static class Beans {
        /** 행의 created_at(DB 현재 시각)이 항상 retry-after 이전이 되도록 먼 미래 */
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2099-01-01T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        }

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }

    @Autowired private EventOutboxService outboxService;
    @Autowired private OutboxEventRepository outboxRepository;
    @MockitoBean private SnsEventPublisher sns;

    private OutboxEvent save(String eventId, String aggregateId) {
        return outboxRepository.save(OutboxEvent.of(eventId, OutboxEvent.AggregateType.ORDER, aggregateId,
                "OrderPaid", "{\"buyerId\":1}"));
    }

    @Test
    @DisplayName("10회째 실패하면 FAILED — 발행기가 다시 잡지 않고, 뒤에 들어온 NEW 행은 정상 발행")
    void failedRowLeavesQueue() {
        // given — SNS 가 이 행만 계속 거절
        OutboxEvent broken = save("00000000-0000-0000-0000-000000000016", "O-BROKEN");
        doThrow(new IllegalStateException("SNS 거절")).when(sns)
                .send(eq("OrderPaid"), eq("O-BROKEN"), anyString(), anyString());

        // when — 9번 실패까지는 NEW
        for (int i = 0; i < 9; i++) {
            outboxService.publishPending();
        }
        assertThat(outboxRepository.findById(broken.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxEvent.Status.NEW);
        outboxService.publishPending();

        // then — 10번째에 FAILED
        OutboxEvent failed = outboxRepository.findById(broken.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OutboxEvent.Status.FAILED);
        assertThat(failed.getAttemptCount()).isEqualTo(10);
        assertThat(failed.getLastError()).contains("SNS 거절");

        // when — 새 이벤트가 들어온 뒤 발행기가 다시 돈다
        clearInvocations(sns);
        OutboxEvent fresh = save("00000000-0000-0000-0000-000000000017", "O-FRESH");
        outboxService.publishPending();

        // then — FAILED 행은 다시 보내지 않고, 새 행은 SENT
        verify(sns, never()).send(eq("OrderPaid"), eq("O-BROKEN"), anyString(), anyString());
        verify(sns).send(eq("OrderPaid"), eq("O-FRESH"), anyString(), anyString());
        assertThat(outboxRepository.findById(fresh.getId()).orElseThrow().isSent()).isTrue();
        assertThat(outboxRepository.findById(broken.getId()).orElseThrow().getAttemptCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("FAILED 행은 send 를 직접 불러도 다시 보내지 않는다")
    void sendSkipsFailedRow() {
        // given
        OutboxEvent row = save("00000000-0000-0000-0000-000000000018", "O-SKIP");
        doThrow(new IllegalStateException("SNS 거절")).when(sns)
                .send(eq("OrderPaid"), eq("O-SKIP"), anyString(), anyString());
        for (int i = 0; i < 10; i++) {
            outboxService.send(row.getId());
        }
        clearInvocations(sns);

        // when
        outboxService.send(row.getId());

        // then
        verify(sns, never()).send(anyString(), anyString(), anyString(), anyString());
        assertThat(outboxRepository.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxEvent.Status.FAILED);
    }
}
