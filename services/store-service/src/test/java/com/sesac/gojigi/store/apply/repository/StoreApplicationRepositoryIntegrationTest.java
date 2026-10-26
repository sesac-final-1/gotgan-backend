package com.sesac.gojigi.store.apply.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.store.TestcontainersConfiguration;
import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.entity.BizType;
import com.sesac.gojigi.store.apply.entity.ExpectedMonthlySales;
import com.sesac.gojigi.store.apply.entity.RepresentativeProduct;
import com.sesac.gojigi.store.apply.entity.StoreApplication;
import com.sesac.gojigi.store.store.repository.StoreRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** 실제 MySQL + Flyway V1 · V2 + 시연 시드(스토어 1 · 2 와 승인 신청서) 위에서 매핑 · 중복 검사 쿼리 확인 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class StoreApplicationRepositoryIntegrationTest {

    private static final List<ApplicationStatus> IN_PROGRESS = List.of(ApplicationStatus.PENDING, ApplicationStatus.SUPPLEMENT);

    @Autowired
    private StoreApplicationRepository storeApplicationRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("신청서를 저장하면 대표 상품 JSON · 상태 · 시각이 그대로 다시 읽힌다")
    void save_newApplication_roundTripsJsonAndStatus() {
        // given
        LocalDateTime submittedAt = LocalDateTime.of(2026, 10, 22, 14, 5, 11);
        StoreApplication saved = storeApplicationRepository.saveAndFlush(application(4L, "새 스토어", "new-store", submittedAt));
        entityManager.clear();

        // when
        StoreApplication found = storeApplicationRepository.findById(saved.getId()).orElseThrow();

        // then
        assertThat(found.getRepresentativeProducts()).containsExactly(
                new RepresentativeProduct("리넨 셔츠", "셔츠", 39000),
                new RepresentativeProduct("리넨 팬츠", "팬츠", 45000));
        assertThat(found.getStatus()).isEqualTo(ApplicationStatus.PENDING);
        assertThat(found.getTermsAgreedAt()).isEqualTo(submittedAt);
        assertThat(found.getStatusChangedAt()).isEqualTo(submittedAt);
        assertThat(found.getExpectedMonthlySales()).isEqualTo(ExpectedMonthlySales.FROM_5M_TO_20M);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("진행 중 신청 · slug · 스토어명 검사는 심사대기 · 보완요청 신청만 본다")
    void exists_inProgressApplication_matchesOnlyPendingOrSupplement() {
        // given
        storeApplicationRepository.saveAndFlush(application(4L, "Linen Lab", "linen-lab", LocalDateTime.now()));

        // when · then
        assertThat(storeApplicationRepository.existsByUserIdAndStatusIn(4L, IN_PROGRESS)).isTrue();
        assertThat(storeApplicationRepository.existsByUserIdAndStatusIn(5L, IN_PROGRESS)).isFalse();
        assertThat(storeApplicationRepository.existsBySlugAndStatusIn("linen-lab", IN_PROGRESS)).isTrue();
        assertThat(storeApplicationRepository.existsByNormalizedStoreNameAndStatusIn("linenlab", IN_PROGRESS)).isTrue();
        // 시드의 승인된 신청서(산골농부 박씨네)는 진행 중이 아니라 신청서 쪽 검사에는 걸리지 않는다 — 스토어 쪽 검사가 막음
        assertThat(storeApplicationRepository.existsByNormalizedStoreNameAndStatusIn("산골농부 박씨네", IN_PROGRESS)).isFalse();
    }

    @Test
    @DisplayName("가장 최근 반려 신청을 상태 변경 시각 기준으로 찾는다")
    void findFirstByUserIdAndStatus_twoRejections_returnsLatest() {
        // given
        Long older = storeApplicationRepository.saveAndFlush(application(5L, "옛 스토어", "old-store", LocalDateTime.of(2026, 8, 1, 10, 0))).getId();
        Long latest = storeApplicationRepository.saveAndFlush(application(5L, "새 스토어", "new-store", LocalDateTime.of(2026, 10, 1, 10, 0))).getId();
        update("UPDATE store_application SET status = 'REJECTED' WHERE store_application_id IN (" + older + ", " + latest + ")");

        // when · then
        assertThat(storeApplicationRepository.findFirstByUserIdAndStatusOrderByStatusChangedAtDesc(5L, ApplicationStatus.REJECTED))
                .get().extracting(StoreApplication::getId).isEqualTo(latest);
    }

    @Test
    @DisplayName("스토어 검사는 폐점 스토어도 본다 — UK 가 모든 행에 걸리므로")
    void storeExists_closedStore_stillMatches() {
        // given — 시드 스토어 2(user 3, 토담공방)를 폐점
        update("UPDATE store SET status = 'CLOSED' WHERE store_id = 2");

        // when · then
        assertThat(storeRepository.existsByUserId(3L)).isTrue();
        assertThat(storeRepository.existsByUserId(4L)).isFalse();
        assertThat(storeRepository.existsBySlug(slugOfStore(2L))).isTrue();
        assertThat(storeRepository.existsByNormalizedStoreName("토담공방")).isTrue();
    }

    @Test
    @DisplayName("스토어명은 공백 · 대소문자를 무시하고 비교한다")
    void existsByNormalizedStoreName_spacesAndCase_ignored() {
        // given
        update("UPDATE store SET store_name = 'Gotgan Living' WHERE store_id = 2");

        // when · then
        assertThat(storeRepository.existsByNormalizedStoreName("gotganliving")).isTrue();
        assertThat(storeRepository.existsByNormalizedStoreName("gotganlivings")).isFalse();
    }

    private StoreApplication application(Long userId, String storeName, String slug, LocalDateTime submittedAt) {
        return StoreApplication.builder()
                .userId(userId).categoryId(1L).bizType(BizType.INDIVIDUAL)
                .companyName("상호").ownerName("홍길동").bizRegNo("000-00-00099").ecommerceRegNo("2026-서울성동-0000")
                .managerPhone("010-0000-1234").managerEmail("seller@example.com").businessAddress("서울시 성동구")
                .storeName(storeName).slug(slug).intro("<p>소개</p>")
                .representativeProducts(List.of(
                        new RepresentativeProduct("리넨 셔츠", "셔츠", 39000),
                        new RepresentativeProduct("리넨 팬츠", "팬츠", 45000)))
                .expectedMonthlySales(ExpectedMonthlySales.FROM_5M_TO_20M)
                .bankName("국민").accountNo("000000-00-000000").accountHolder("홍길동")
                .marketingAgreed(false).submittedAt(submittedAt)
                .build();
    }

    private String slugOfStore(Long storeId) {
        return (String) entityManager.createNativeQuery("SELECT slug FROM store WHERE store_id = " + storeId).getSingleResult();
    }

    private void update(String sql) {
        entityManager.createNativeQuery(sql).executeUpdate();
        entityManager.clear();
    }
}
