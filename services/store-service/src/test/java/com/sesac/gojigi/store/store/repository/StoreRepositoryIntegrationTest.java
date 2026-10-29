package com.sesac.gojigi.store.store.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.store.TestcontainersConfiguration;
import com.sesac.gojigi.store.store.entity.Store;
import com.sesac.gojigi.store.store.entity.StoreGrade;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import jakarta.persistence.EntityManager;
import java.util.List;
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

    @Test
    @DisplayName("관리자 목록은 시드 9개를 개설 최신순으로, 대표자 · 사업자번호는 신청서에서, 등급은 DB 값")
    void findAllForAdmin_withoutStatus_returnsAllNewestFirst() {
        // when
        List<StoreWithApplicant> rows = storeRepository.findAllForAdmin(null);

        // then — 시드 개설일: 19(210일 전) · 15(301) · 2(455) · 17(540) · 11(612) · 18(720) · 12(890) · 1(980) · 13(1320)
        assertThat(rows).extracting(r -> r.store().getId()).containsExactly(19L, 15L, 2L, 17L, 11L, 18L, 12L, 1L, 13L);
        StoreWithApplicant park = rows.stream().filter(r -> r.store().getId() == 1L).findFirst().orElseThrow();
        assertThat(park.ownerName()).isEqualTo("박영철");
        assertThat(park.bizRegNo()).isEqualTo("000-00-00006");
        assertThat(park.store().getGrade()).isEqualTo(StoreGrade.BIG_POWER);
        assertThat(park.store().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("개설 시각이 같으면 스토어 ID 큰 순")
    void findAllForAdmin_sameCreatedAt_higherIdFirst() {
        // given — 스토어 13 을 스토어 1 과 같은 시각으로
        update("UPDATE store SET created_at = (SELECT t.created_at FROM (SELECT created_at FROM store WHERE store_id = 1) t) WHERE store_id = 13");

        // when · then
        assertThat(storeRepository.findAllForAdmin(null)).extracting(r -> r.store().getId()).endsWith(13L, 1L);
    }

    @Test
    @DisplayName("상태 필터는 그 상태만 · 소프트 삭제된 스토어는 빠지고 폐점은 남는다")
    void findAllForAdmin_withStatus_filtersAndExcludesSoftDeleted() {
        // given
        update("UPDATE store SET status = 'CLOSED' WHERE store_id = 2");
        update("UPDATE store SET deleted_at = NOW(6) WHERE store_id = 17");

        // when · then
        assertThat(storeRepository.findAllForAdmin(StoreStatus.SUSPENDED)).extracting(r -> r.store().getId()).containsExactly(15L);
        assertThat(storeRepository.findAllForAdmin(StoreStatus.CLOSED)).extracting(r -> r.store().getId()).containsExactly(2L);
        assertThat(storeRepository.findAllForAdmin(null)).extracting(r -> r.store().getId())
                .hasSize(8).doesNotContain(17L).contains(2L);
    }

    @Test
    @DisplayName("공개 목록은 운영 중 8개를 개설 최신순으로 — 정지 15 는 빠지고, 카테고리는 신청서 값")
    void findAllActiveForPublic_seed_returnsActiveNewestFirstWithCategory() {
        // when
        List<StoreWithCategory> rows = storeRepository.findAllActiveForPublic();

        // then — 관리자 목록 순서(19 · 15 · 2 · 17 · 11 · 18 · 12 · 1 · 13)에서 정지 15 만 빠짐
        assertThat(rows).extracting(r -> r.store().getId()).containsExactly(19L, 2L, 17L, 11L, 18L, 12L, 1L, 13L);
        assertThat(rows).filteredOn(r -> r.store().getId() == 1L).singleElement()
                .extracting(StoreWithCategory::categoryId).isEqualTo(1L);   // 식품
        assertThat(rows).filteredOn(r -> r.store().getId() == 2L).singleElement()
                .extracting(StoreWithCategory::categoryId).isEqualTo(2L);   // 리빙
    }

    @Test
    @DisplayName("공개 목록에서 폐점 · 소프트 삭제 스토어도 빠진다")
    void findAllActiveForPublic_excludesClosedAndSoftDeleted() {
        // given
        update("UPDATE store SET status = 'CLOSED' WHERE store_id = 2");
        update("UPDATE store SET deleted_at = NOW(6) WHERE store_id = 17");

        // when · then
        assertThat(storeRepository.findAllActiveForPublic()).extracting(r -> r.store().getId())
                .containsExactly(19L, 11L, 18L, 12L, 1L, 13L);
    }

    private void update(String sql) {
        entityManager.createNativeQuery(sql).executeUpdate();
        entityManager.clear();
    }
}
