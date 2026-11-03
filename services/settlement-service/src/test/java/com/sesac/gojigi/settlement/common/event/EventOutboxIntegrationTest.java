package com.sesac.gojigi.settlement.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.settlement.TestcontainersConfiguration;
import com.sesac.gojigi.settlement.common.config.SchedulingConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 발행기 조회 — 시도 한도에 닿은 행은 빠지고 뒤의 새 행이 나간다 (실제 MySQL, 11/3) */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, SchedulingConfig.class, EventOutboxService.class, EventOutboxIntegrationTest.Config.class})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // 발행기가 자기 트랜잭션으로 읽게 — 저장은 바로 커밋
class EventOutboxIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-29T01:00:00Z"), ZoneId.of("Asia/Seoul"));
        }

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }

    @MockitoBean SnsEventPublisher sns;
    @Autowired EventOutboxService service;
    @Autowired EventOutboxRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Value("${app.events.outbox.max-attempts}") int maxAttempts;

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM event_outbox");
    }

    @Test
    @DisplayName("시도 한도(10)에 닿은 행은 발행기 대상에서 빠진다 — 영구 실패 행 50건이 앞에 있어도 뒤의 새 이벤트가 나간다")
    void publishPending_whenRowsPastLimit_skipsThemAndSendsNewer() {
        // given — 한도에 닿은 행 50건(발행기 1회 조회 분량) + 그 뒤에 쌓인 새 행
        List<EventOutbox> stuck = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            EventOutbox row = EventOutbox.pending("evt-stuck-" + i, "SettlementPaid", "31", "{}");
            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                row.markFailed("sns timeout");
            }
            stuck.add(row);
        }
        repository.saveAll(stuck);
        EventOutbox fresh = repository.save(EventOutbox.pending("evt-fresh", "SettlementPaid", "32", "{}"));
        // created_at 은 DB 실제 시각 — 고정 시계(10/29 10:00)보다 과거로, 영구 실패 행이 더 오래되게 돌려 둔다
        jdbc.update("UPDATE event_outbox SET created_at = '2026-10-29 09:00:00' WHERE event_id LIKE 'evt-stuck-%'");
        jdbc.update("UPDATE event_outbox SET created_at = '2026-10-29 09:30:00' WHERE event_id = 'evt-fresh'");

        // when
        service.publishPending();

        // then
        verify(sns).send(eq("SettlementPaid"), eq("32"), eq("evt-fresh"), anyString());
        verify(sns, never()).send(anyString(), anyString(), startsWith("evt-stuck-"), anyString());
        assertThat(repository.findById(fresh.getId()).orElseThrow().isSent()).isTrue();
        assertThat(repository.findAll()).filteredOn(r -> r.getEventId().startsWith("evt-stuck-"))
                .hasSize(50).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(EventOutbox.Status.PENDING));
    }
}
