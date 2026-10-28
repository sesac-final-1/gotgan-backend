package com.sesac.gojigi.product.store.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.product.TestcontainersConfiguration;
import com.sesac.gojigi.product.common.event.EventEnvelope;
import com.sesac.gojigi.product.store.dto.event.StoreStatusChangedPayload;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** 실제 MySQL + Flyway V3 + 시연 시드 위에서 스냅샷 · 상품 일괄 반영 · 옛 이벤트 무시 · 중복 처리 확인 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, StoreStatusService.class})
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class StoreStatusServiceIntegrationTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-10-28T14:30:00+09:00");

    @Autowired
    private StoreStatusService service;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("시드 스토어 1 정지 → 스냅샷 생성 · 그 스토어 상품만 전부 숨김 · 처리 기록 1건")
    void applyStatusChanged_whenSuspended_hidesStoreProducts() {
        // given
        int storeProducts = count("SELECT COUNT(*) FROM product WHERE store_id = 1");

        // when
        StoreStatusService.Result result = service.applyStatusChanged(event("e-1", 1L, "SUSPENDED", AT));

        // then
        assertThat(result).isEqualTo(StoreStatusService.Result.APPLIED);
        assertThat(storeProducts).isPositive();
        assertThat(count("SELECT COUNT(*) FROM product WHERE store_id = 1 AND NOT is_store_active")).isEqualTo(storeProducts);
        assertThat(count("SELECT COUNT(*) FROM product WHERE store_id = 2 AND NOT is_store_active")).isZero();
        Map<String, Object> snapshot = jdbc.queryForMap("SELECT status, status_changed_at FROM store_status WHERE store_id = 1");
        assertThat(snapshot.get("status")).isEqualTo("SUSPENDED");
        assertThat(snapshot.get("status_changed_at")).isEqualTo(LocalDateTime.of(2026, 10, 28, 14, 30));
        assertThat(count("SELECT COUNT(*) FROM processed_event WHERE event_id = 'e-1'")).isOne();
    }

    @Test
    @DisplayName("해제 뒤 늦게 도착한 옛 정지 이벤트는 무시 — 상품이 다시 숨겨지지 않는다")
    void applyStatusChanged_whenOldSuspendArrivesLate_keepsActive() {
        // given — 정지(14:30) → 해제(15:00) 반영
        service.applyStatusChanged(event("e-suspend", 1L, "SUSPENDED", AT));
        service.applyStatusChanged(event("e-release", 1L, "ACTIVE", AT.plusMinutes(30)));

        // when — 14:10 에 났던 정지 이벤트가 이제 도착
        StoreStatusService.Result result = service.applyStatusChanged(event("e-late", 1L, "SUSPENDED", AT.minusMinutes(20)));

        // then
        assertThat(result).isEqualTo(StoreStatusService.Result.STALE_IGNORED);
        assertThat(count("SELECT COUNT(*) FROM product WHERE store_id = 1 AND NOT is_store_active")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM store_status WHERE store_id = 1", String.class)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("상품이 없는 스토어의 이벤트도 스냅샷은 남는다 — 그 뒤 등록하는 상품의 초기값")
    void applyStatusChanged_whenNoProducts_keepsSnapshot() {
        // when
        StoreStatusService.Result result = service.applyStatusChanged(event("e-new", 777L, "CLOSED", AT));

        // then
        assertThat(result).isEqualTo(StoreStatusService.Result.APPLIED);
        assertThat(jdbc.queryForObject("SELECT status FROM store_status WHERE store_id = 777", String.class)).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("같은 eventId 를 두 번 받으면 두 번째는 DUPLICATE — 처리 기록도 1건")
    void applyStatusChanged_whenRedelivered_isDuplicate() {
        // given
        service.applyStatusChanged(event("e-dup", 2L, "SUSPENDED", AT));

        // when · then
        assertThat(service.applyStatusChanged(event("e-dup", 2L, "SUSPENDED", AT))).isEqualTo(StoreStatusService.Result.DUPLICATE);
        assertThat(count("SELECT COUNT(*) FROM processed_event WHERE event_id = 'e-dup'")).isOne();
    }

    @Test
    @DisplayName("시드의 정지 스토어 15 는 스냅샷이 있다 — 15 의 옛 해제 이벤트는 무시된다")
    void seed_suspendedStore_hasSnapshot() {
        // given
        LocalDateTime seeded = jdbc.queryForObject("SELECT status_changed_at FROM store_status WHERE store_id = 15", LocalDateTime.class);

        // when
        StoreStatusService.Result result = service.applyStatusChanged(
                event("e-15", 15L, "ACTIVE", seeded.minusDays(1).atZone(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime()));

        // then
        assertThat(result).isEqualTo(StoreStatusService.Result.STALE_IGNORED);
        assertThat(count("SELECT COUNT(*) FROM product WHERE store_id = 15 AND is_store_active")).isZero();
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private static EventEnvelope<StoreStatusChangedPayload> event(String eventId, Long storeId, String status, OffsetDateTime at) {
        return new EventEnvelope<>(eventId, "StoreStatusChanged", at, String.valueOf(storeId), 1,
                new StoreStatusChangedPayload(storeId, status));
    }
}
