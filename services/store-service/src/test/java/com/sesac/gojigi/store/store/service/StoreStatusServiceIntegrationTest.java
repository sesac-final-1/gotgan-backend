package com.sesac.gojigi.store.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.store.TestcontainersConfiguration;
import com.sesac.gojigi.store.common.event.EventOutboxService;
import com.sesac.gojigi.store.common.event.OutboxEvent;
import com.sesac.gojigi.store.common.event.OutboxEventRepository;
import com.sesac.gojigi.store.common.event.SnsEventPublisher;
import com.sesac.gojigi.store.store.entity.StoreActorType;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.repository.StoreRepository;
import com.sesac.gojigi.store.store.repository.StoreStatusHistoryRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 MySQL + Flyway + 시연 시드 위에서 잠금 조회 · 상태 · 이력 저장 · 커밋 뒤 StoreStatusChanged 발행 확인 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, StoreStatusService.class, EventOutboxService.class,
        StoreStatusServiceIntegrationTest.FixedClock.class})
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class StoreStatusServiceIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 14, 30);

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW.atZone(KST).toInstant(), KST);
        }

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }

    @MockitoBean
    private SnsEventPublisher snsEventPublisher;

    @Autowired
    private StoreStatusService service;
    @Autowired
    private StoreRepository storeRepository;
    @Autowired
    private StoreStatusHistoryRepository storeStatusHistoryRepository;
    @Autowired
    private OutboxEventRepository outboxEventRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("잠금 조회는 소프트 삭제된 스토어를 찾지 않는다")
    void findForUpdate_whenSoftDeleted_returnsEmpty() {
        // given
        jdbcTemplate.update("UPDATE store SET deleted_at = NOW(6) WHERE store_id = 2");

        // when · then
        assertThat(storeRepository.findForUpdate(1L)).isPresent();
        assertThat(storeRepository.findForUpdate(2L)).isEmpty();
    }

    @Test
    @DisplayName("이미 정지된 시드 스토어 15 를 다시 정지하면 이력 · 아웃박스 행이 생기지 않는다")
    void changeByAdmin_whenAlreadySuspended_writesNothing() {
        // given
        long historiesBefore = storeStatusHistoryRepository.count();

        // when
        service.changeByAdmin(15L, StoreStatus.SUSPENDED, "다시 정지");

        // then
        assertThat(storeStatusHistoryRepository.count()).isEqualTo(historiesBefore);
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("정지가 커밋되면 상태 · 사유 · 이력이 저장되고 StoreStatusChanged 봉투를 한 번 보낸다 — payload {storeId, status}")
    void changeByAdmin_whenCommitted_persistsAndPublishesEnvelope() {
        // given
        service.changeByAdmin(1L, StoreStatus.SUSPENDED, "허위 광고 신고 3건 확인");
        verify(snsEventPublisher, never()).send(anyString(), anyString(), anyString(), anyString());

        // when
        TestTransaction.flagForCommit();
        TestTransaction.end();

        // then
        try {
            entityManager.clear();
            assertThat(storeRepository.findById(1L)).get().satisfies(s -> {
                assertThat(s.getStatus()).isEqualTo(StoreStatus.SUSPENDED);
                assertThat(s.getStatusReason()).isEqualTo("허위 광고 신고 3건 확인");
            });
            assertThat(storeStatusHistoryRepository.findAllByStoreIdOrderByIdAsc(1L)).last().satisfies(h -> {
                assertThat(h.getFromStatus()).isEqualTo(StoreStatus.ACTIVE);
                assertThat(h.getToStatus()).isEqualTo(StoreStatus.SUSPENDED);
                assertThat(h.getActorType()).isEqualTo(StoreActorType.ADMIN);
                assertThat(h.getReason()).isEqualTo("허위 광고 신고 3건 확인");
            });

            ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
            verify(snsEventPublisher).send(eq("StoreStatusChanged"), eq("1"), anyString(), json.capture());
            JsonNode envelope = JsonMapper.builder().build().readTree(json.getValue());
            assertThat(envelope.get("occurredAt").asString()).isEqualTo("2026-10-28T14:30:00+09:00");
            assertThat(envelope.get("aggregateId").asString()).isEqualTo("1");
            assertThat(envelope.get("payload").get("storeId").asLong()).isEqualTo(1L);
            assertThat(envelope.get("payload").get("status").asString()).isEqualTo("SUSPENDED");
            assertThat(outboxEventRepository.findAll()).singleElement()
                    .satisfies(row -> assertThat(row.getStatus()).isEqualTo(OutboxEvent.Status.SENT));
        } finally { // 커밋된 변경은 다른 테스트에 남지 않게 되돌린다
            jdbcTemplate.update("DELETE FROM outbox_event");
            jdbcTemplate.update("DELETE FROM store_status_history WHERE store_id = 1 AND to_status = 'SUSPENDED'");
            jdbcTemplate.update("UPDATE store SET status = 'ACTIVE', status_reason = NULL WHERE store_id = 1");
        }
    }
}
