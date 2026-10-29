package com.sesac.gojigi.settlement.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

/** 발행 실패 로그 — 한도에 닿은 한 번만 error + 스택, 그 뒤는 한 줄 warn (store 아웃박스와 같은 방식, 10/29) */
@ExtendWith(OutputCaptureExtension.class)
class EventOutboxServiceTest {

    private static final int MAX_ATTEMPTS = 3;

    private final EventOutboxRepository repository = mock(EventOutboxRepository.class);
    private final SnsEventPublisher sns = mock(SnsEventPublisher.class);
    private EventOutboxService service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        given(txManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        service = new EventOutboxService(repository, sns, mock(ApplicationEventPublisher.class), JsonMapper.builder().build(), txManager,
                Clock.fixed(Instant.parse("2026-10-29T01:00:00Z"), ZoneId.of("Asia/Seoul")), Duration.ofSeconds(10), MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("한도 전 실패는 warn(발행기가 다시 보냄), 한도에 닿은 실패만 error + 스택, 그 뒤 실패는 한 줄 warn")
    void send_whenFailsPastLimit_logsErrorOnce(CapturedOutput output) {
        EventOutbox row = EventOutbox.pending("evt-1", "SettlementPaid", "31", "{}");
        given(repository.findById(7L)).willReturn(Optional.of(row));
        willThrow(new IllegalStateException("sns timeout")).given(sns).send(anyString(), anyString(), anyString(), anyString());

        for (int i = 0; i < MAX_ATTEMPTS + 2; i++) {
            service.send(7L);
        }

        assertThat(row.isSent()).isFalse();
        assertThat(row.getAttemptCount()).isEqualTo(MAX_ATTEMPTS + 2);
        String logs = output.getOut();
        assertThat(logs.split("발행기가 다시 보냄", -1)).hasSize(MAX_ATTEMPTS);          // 1 · 2회째
        assertThat(logs.split("회 실패 — 수동 확인 필요", -1)).hasSize(2);               // 한도(3회째) 한 번만 error
        assertThat(logs.split("계속 실패 — 수동 확인 필요", -1)).hasSize(3);              // 4 · 5회째 한 줄 warn
    }
}
