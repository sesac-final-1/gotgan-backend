package com.sesac.gojigi.store.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.store.TestcontainersConfiguration;
import com.sesac.gojigi.store.common.event.EventOutboxService;
import com.sesac.gojigi.store.common.event.OutboxEvent;
import com.sesac.gojigi.store.common.event.OutboxEventRepository;
import com.sesac.gojigi.store.common.event.SnsEventPublisher;
import com.sesac.gojigi.store.store.client.OrderClientAdapter;
import com.sesac.gojigi.store.store.client.SettlementClientAdapter;
import com.sesac.gojigi.store.store.client.dto.SettlementOutstandingResponse;
import com.sesac.gojigi.store.store.dto.response.InternalStoreResponse;
import com.sesac.gojigi.store.store.dto.response.StoreCloseResponse;
import com.sesac.gojigi.store.store.entity.StoreActorType;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.repository.StoreRepository;
import com.sesac.gojigi.store.store.repository.StoreStatusHistoryRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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
@Import({TestcontainersConfiguration.class, StoreStatusService.class, StoreQueryService.class, ImageUrls.class, EventOutboxService.class,
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
    @MockitoBean
    private OrderClientAdapter orderClientAdapter;
    @MockitoBean
    private SettlementClientAdapter settlementClientAdapter;

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
        InternalStoreResponse response = service.changeByAdmin(1L, StoreStatus.SUSPENDED, "허위 광고 신고 3건 확인");
        assertThat(response.status()).isEqualTo(StoreStatus.SUSPENDED);
        assertThat(response.statusReason()).isEqualTo("허위 광고 신고 3건 확인");
        assertThat(response.statusHistory()).last().satisfies(h -> {   // 방금 남긴 이력까지 응답에 담긴다
            assertThat(h.toStatus()).isEqualTo(StoreStatus.SUSPENDED);
            assertThat(h.changedAt()).isNotNull();
        });
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

    @Test
    @DisplayName("정지 스토어 15 폐점이 커밋되면 CLOSED · 사유 비움 · 이력(SELLER), StoreStatusChanged{CLOSED} 봉투 1회 — 다시 폐점은 처음 시각 그대로")
    void closeBySeller_whenCommitted_closesAndPublishesClosed() {
        // given — order · settlement 확인 통과
        given(orderClientAdapter.inProgressCount(15L)).willReturn(0L);
        given(settlementClientAdapter.outstanding(15L)).willReturn(new SettlementOutstandingResponse(15L, 0, 0, 0, true));
        StoreCloseResponse response = service.closeBySeller(15L);
        assertThat(response.closedAt()).isEqualTo(OffsetDateTime.parse("2026-10-28T14:30:00+09:00"));

        // when
        TestTransaction.flagForCommit();
        TestTransaction.end();

        // then
        try {
            entityManager.clear();
            assertThat(storeRepository.findById(15L)).get().satisfies(s -> {
                assertThat(s.getStatus()).isEqualTo(StoreStatus.CLOSED);
                assertThat(s.getStatusReason()).isNull();   // 정지 사유가 남지 않게
            });
            assertThat(storeStatusHistoryRepository.findAllByStoreIdOrderByIdAsc(15L)).last().satisfies(h -> {
                assertThat(h.getFromStatus()).isEqualTo(StoreStatus.SUSPENDED);
                assertThat(h.getToStatus()).isEqualTo(StoreStatus.CLOSED);
                assertThat(h.getActorType()).isEqualTo(StoreActorType.SELLER);
            });
            ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
            verify(snsEventPublisher).send(eq("StoreStatusChanged"), eq("15"), anyString(), json.capture());
            assertThat(JsonMapper.builder().build().readTree(json.getValue()).get("payload").get("status").asString()).isEqualTo("CLOSED");

            StoreCloseResponse again = service.closeBySeller(15L);   // 이미 폐점 — 다른 서비스 확인 없이 처음 시각
            assertThat(again.closedAt()).isNotNull();
            verify(orderClientAdapter).inProgressCount(15L);         // 처음 한 번만
        } finally { // 커밋된 변경은 시드 값(정지 · 사유 · 정지 이력까지)으로 되돌린다
            jdbcTemplate.update("DELETE FROM outbox_event");
            jdbcTemplate.update("DELETE FROM store_status_history WHERE store_id = 15 AND to_status = 'CLOSED'");
            jdbcTemplate.update("UPDATE store SET status = 'SUSPENDED', status_reason = '반품 미처리 3건 — 처리 완료 후 해제' WHERE store_id = 15");
        }
    }
}
