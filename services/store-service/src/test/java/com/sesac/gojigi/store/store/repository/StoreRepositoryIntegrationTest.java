package com.sesac.gojigi.store.store.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.store.TestcontainersConfiguration;
import com.sesac.gojigi.store.store.entity.Store;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** 실제 MySQL + Flyway V1 · V2 + 시연 시드(스토어 1 · 2) 위에서 조회 조건 · 상태 매핑 확인 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class StoreRepositoryIntegrationTest {

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("시드 스토어 1 은 판매자 user 2 의 산골농부 박씨네, 운영 중")
    void findByIdAndDeletedAtIsNull_seedStore_returnsStore() {
        // when
        Store store = storeRepository.findByIdAndDeletedAtIsNull(1L).orElseThrow();

        // then
        assertThat(store.getUserId()).isEqualTo(2L);
        assertThat(store.getStoreName()).isEqualTo("산골농부 박씨네");
        assertThat(store.getStatus()).isEqualTo(StoreStatus.ACTIVE);
    }

    @Test
    @DisplayName("배송 정책 컬럼이 매핑된다 — 시드 스토어 1 은 3,000원 · 3만 원 이상 무료 · 우체국택배")
    void findByIdAndDeletedAtIsNull_seedStore_mapsShippingPolicy() {
        // when
        Store store = storeRepository.findByIdAndDeletedAtIsNull(1L).orElseThrow();

        // then
        assertThat(store.getShippingFee()).isEqualTo(3000L);
        assertThat(store.getFreeShippingThreshold()).isEqualTo(30000L);
        assertThat(store.getCourier()).isEqualTo("우체국택배");
    }

    @Test
    @DisplayName("무료배송 기준 · 택배사가 NULL 이면 null 로 읽힌다 (승인 직후 스토어)")
    void findByIdAndDeletedAtIsNull_nullShippingColumns_returnsNull() {
        // given
        update("UPDATE store SET free_shipping_threshold = NULL, courier = NULL WHERE store_id = 2");

        // when
        Store store = storeRepository.findByIdAndDeletedAtIsNull(2L).orElseThrow();

        // then
        assertThat(store.getShippingFee()).isEqualTo(3500L);
        assertThat(store.getFreeShippingThreshold()).isNull();
        assertThat(store.getCourier()).isNull();
    }

    @Test
    @DisplayName("DB 에 CLOSED 로 저장된 스토어도 조회되고 enum 으로 읽힌다 (폐점 스토어도 내부 API 가 응답)")
    void findByIdAndDeletedAtIsNull_closedStore_returnsClosed() {
        // given
        update("UPDATE store SET status = 'CLOSED' WHERE store_id = 2");

        // when · then
        assertThat(storeRepository.findByIdAndDeletedAtIsNull(2L))
                .get().extracting(Store::getStatus).isEqualTo(StoreStatus.CLOSED);
    }

    @Test
    @DisplayName("소프트 삭제된 스토어와 없는 스토어는 찾지 않는다")
    void findByIdAndDeletedAtIsNull_deletedOrMissing_returnsEmpty() {
        // given
        update("UPDATE store SET deleted_at = NOW(6) WHERE store_id = 2");

        // when · then
        assertThat(storeRepository.findByIdAndDeletedAtIsNull(2L)).isEmpty();
        assertThat(storeRepository.findByIdAndDeletedAtIsNull(999L)).isEmpty();
    }

    private void update(String sql) {
        entityManager.createNativeQuery(sql).executeUpdate();
        entityManager.clear();
    }
}
