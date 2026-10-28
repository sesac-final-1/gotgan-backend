package com.sesac.gojigi.store.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.store.TestcontainersConfiguration;
import com.sesac.gojigi.store.common.config.SchedulingConfig;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sns.model.SnsException;
import tools.jackson.databind.json.JsonMapper;

/** 실제 MySQL + 실제 커밋 · 롤백 · ShedLock 잠금 위에서 아웃박스 동작 확인 — 테스트 트랜잭션을 끄고 업무 트랜잭션을 직접 연다 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, SchedulingConfig.class, EventOutboxService.class, EventOutboxIntegrationTest.Config.class})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EventOutboxIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 14, 30);

    @TestConfiguration
    static class Config {
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
    private SnsEventPublisher sns;
    @Autowired
    private EventOutboxService service;
    @Autowired
    private OutboxEventRepository repository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM outbox_event");
    }

    @Test
    @DisplayName("업무가 커밋되면 직후 한 번 발행하고 행은 SENT · 시도 1회로 남는다")
    void enqueue_whenCommitted_sendsRightAfterCommit() {
        // when
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            service.enqueue("StoreApproved", "3", "evt-commit", NOW, Map.of("storeId", 3));
            verify(sns, never()).send(anyString(), anyString(), anyString(), anyString()); // 커밋 전에는 안 보냄
        });

        // then
        verify(sns).send(eq("StoreApproved"), eq("3"), eq("evt-commit"), anyString());
        OutboxEvent row = repository.findAll().getFirst();
        assertThat(row.getStatus()).isEqualTo(OutboxEvent.Status.SENT);
        assertThat(row.getSentAt()).isEqualTo(NOW);
        assertThat(row.getAttemptCount()).isEqualTo(1);
        assertThat(row.getOccurredAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("업무가 롤백되면 행도 남지 않고 발행도 없다")
    void enqueue_whenRolledBack_leavesNothing() {
        // when
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            service.enqueue("StoreApproved", "3", "evt-rollback", NOW, Map.of("storeId", 3));
            s.setRollbackOnly();
        });

        // then
        assertThat(repository.count()).isZero();
        verify(sns, never()).send(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("업무 트랜잭션 밖에서 enqueue 하면 거절한다 (MANDATORY) — 이벤트만 남고 업무는 없는 상황 방지")
    void enqueue_whenNoTransaction_throws() {
        assertThatThrownBy(() -> service.enqueue("StoreApproved", "3", "evt-none", NOW, Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("같은 eventId 는 두 번 남길 수 없다 (uk_outbox_event_event_id) — 그 업무 트랜잭션은 롤백")
    void enqueue_whenSameEventIdTwice_isRejected() {
        // given
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(s -> service.enqueue("StoreApproved", "3", "evt-dup", NOW, Map.of("storeId", 3)));

        // when · then
        assertThatThrownBy(() -> tx.executeWithoutResult(
                s -> service.enqueue("StoreApproved", "3", "evt-dup", NOW, Map.of("storeId", 3))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("커밋 직후 발행이 실패해도 업무는 커밋되고, 행은 NEW 로 남았다가 발행기가 다시 보내 SENT 가 된다")
    void publishPending_whenFirstSendFailed_resendsLater() {
        // given — SNS 장애 중에 커밋 (발행기 조건 retry-after 10초를 넘기도록 1분 전 업무)
        willThrow(SnsException.builder().message("timeout").build())
                .given(sns).send(anyString(), anyString(), anyString(), anyString());
        new TransactionTemplate(transactionManager).executeWithoutResult(
                s -> service.enqueue("StoreApproved", "3", "evt-retry", NOW.minusMinutes(1), Map.of("storeId", 3)));
        OutboxEvent failed = repository.findAll().getFirst();
        assertThat(failed.getStatus()).isEqualTo(OutboxEvent.Status.NEW);
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("timeout");

        // when — SNS 복구 뒤 발행기
        willDoNothing().given(sns).send(anyString(), anyString(), anyString(), anyString());
        service.publishPending();

        // then
        verify(sns, times(2)).send(eq("StoreApproved"), eq("3"), eq("evt-retry"), anyString());
        OutboxEvent sent = repository.findAll().getFirst();
        assertThat(sent.getStatus()).isEqualTo(OutboxEvent.Status.SENT);
        assertThat(sent.getAttemptCount()).isEqualTo(2);
        assertThat(sent.getLastError()).isNull();
    }

    @Test
    @DisplayName("발행기는 retry-after(10초) 안의 NEW 는 건드리지 않는다 — 커밋 직후 발행과 겹치지 않게")
    void publishPending_whenRowTooRecent_skips() {
        // given
        willThrow(SnsException.builder().message("timeout").build())
                .given(sns).send(anyString(), anyString(), anyString(), anyString());
        new TransactionTemplate(transactionManager).executeWithoutResult(
                s -> service.enqueue("StoreApproved", "3", "evt-recent", NOW.minusSeconds(5), Map.of("storeId", 3)));

        // when
        service.publishPending();

        // then
        verify(sns, times(1)).send(anyString(), anyString(), anyString(), anyString()); // 커밋 직후 1번뿐
        assertThat(repository.findAll().getFirst().getStatus()).isEqualTo(OutboxEvent.Status.NEW);
    }
}
