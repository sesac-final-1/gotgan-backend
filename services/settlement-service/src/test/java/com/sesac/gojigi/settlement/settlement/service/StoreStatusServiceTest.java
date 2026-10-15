package com.sesac.gojigi.settlement.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.settlement.common.event.EventEnvelope;
import com.sesac.gojigi.settlement.settlement.dto.event.StoreStatusChangedPayload;
import com.sesac.gojigi.settlement.settlement.entity.SettlementStore;
import com.sesac.gojigi.settlement.settlement.repository.SettlementStoreRepository;
import com.sesac.gojigi.settlement.settlement.service.StoreStatusService.Result;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 이벤트 순서 규칙(10/15 A안) 검증 — 실제 MySQL(Testcontainers) + Flyway V1 · V2.
 * 시나리오: store 가 ① 10:00 정지 ② 10:05 정지 해제 를 발행했는데 SQS 가 ② → ① 순서로 전달
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(StoreStatusService.class)
class StoreStatusServiceTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

    @Autowired StoreStatusService service;
    @Autowired SettlementStoreRepository repository;

    static final long STORE_ID = 1L;
    static final OffsetDateTime T1000 = OffsetDateTime.parse("2026-10-21T10:00:00+09:00");
    static final OffsetDateTime T1005 = OffsetDateTime.parse("2026-10-21T10:05:00+09:00");

    @BeforeEach
    void setUp() {
        repository.save(SettlementStore.approved(STORE_ID, "오롯이 리넨"));
    }

    private static EventEnvelope<StoreStatusChangedPayload> event(String status, OffsetDateTime at) {
        return new EventEnvelope<>(UUID.randomUUID().toString(), "StoreStatusChanged", at, String.valueOf(STORE_ID), 1,
                new StoreStatusChangedPayload(STORE_ID, status));
    }

    private SettlementStore store() {
        return repository.findByStoreId(STORE_ID).orElseThrow();
    }

    @Test
    void 순서대로_도착하면_마지막_상태로_반영() {
        assertThat(service.applyStatusChanged(event("SUSPENDED", T1000))).isEqualTo(Result.APPLIED);
        assertThat(service.applyStatusChanged(event("ACTIVE", T1005))).isEqualTo(Result.APPLIED);

        assertThat(store().getStoreStatus()).isEqualTo("ACTIVE");
        assertThat(store().isPayoutHeld()).isFalse();
    }

    @Test
    void 늦게_도착한_옛_이벤트는_무시하고_최신_상태_유지() {
        assertThat(service.applyStatusChanged(event("ACTIVE", T1005))).isEqualTo(Result.APPLIED);       // ② 먼저
        assertThat(service.applyStatusChanged(event("SUSPENDED", T1000))).isEqualTo(Result.STALE_IGNORED); // ① 나중

        assertThat(store().getStoreStatus()).isEqualTo("ACTIVE");   // 정지로 되돌아가지 않음
        assertThat(store().isPayoutHeld()).isFalse();
        assertThat(store().getStoreStatusChangedAt()).isEqualTo(T1005.toLocalDateTime());
    }

    @Test
    void 같은_eventId_재전달은_중복으로_무시() {
        var e = event("SUSPENDED", T1000);
        assertThat(service.applyStatusChanged(e)).isEqualTo(Result.APPLIED);
        assertThat(service.applyStatusChanged(e)).isEqualTo(Result.DUPLICATE);
        assertThat(store().isPayoutHeld()).isTrue();
    }

    @Test
    void 스냅샷이_없는_스토어는_재시도할_수_있게_처리_기록을_남기지_않음() {
        var e = new EventEnvelope<>(UUID.randomUUID().toString(), "StoreStatusChanged", T1000, "999", 1,
                new StoreStatusChangedPayload(999L, "SUSPENDED"));
        assertThat(service.applyStatusChanged(e)).isEqualTo(Result.STORE_NOT_FOUND);
        assertThat(service.applyStatusChanged(e)).isEqualTo(Result.STORE_NOT_FOUND); // DUPLICATE 아님 — 재시도 가능
    }
}
