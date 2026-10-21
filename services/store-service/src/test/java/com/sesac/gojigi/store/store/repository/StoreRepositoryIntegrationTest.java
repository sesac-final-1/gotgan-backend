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
    @DisplayName("시드 스토어 1 은 판매자 user 2 의 곳간상회, 운영 중")
    void findByIdAndDeletedAtIsNull_seedStore_returnsStore() {
        // when
        Store store = storeRepository.findByIdAndDeletedAtIsNull(1L).orElseThrow();

        // then
        assertThat(store.getUserId()).isEqualTo(2L);
        assertThat(store.getStoreName()).isEqualTo("곳간상회");
        assertThat(store.getStatus()).isEqualTo(StoreStatus.ACTIVE);
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
