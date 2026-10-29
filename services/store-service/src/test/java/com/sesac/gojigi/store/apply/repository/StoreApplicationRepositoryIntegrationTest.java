package com.sesac.gojigi.store.apply.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.store.TestcontainersConfiguration;
import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.entity.BizType;
import com.sesac.gojigi.store.apply.entity.ExpectedMonthlySales;
import com.sesac.gojigi.store.apply.entity.RepresentativeProduct;
import com.sesac.gojigi.store.apply.entity.StoreApplication;
import com.sesac.gojigi.store.store.entity.Store;
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
import org.springframework.data.domain.Limit;
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
        assertThat(storeApplicationRepository.existsByUserIdAndStatusIn(7L, IN_PROGRESS)).isFalse();   // 5 · 6 은 시드의 심사 시연 신청자
        assertThat(storeApplicationRepository.existsBySlugAndStatusIn("linen-lab", IN_PROGRESS)).isTrue();
        assertThat(storeApplicationRepository.existsByNormalizedStoreNameAndStatusIn("linenlab", IN_PROGRESS)).isTrue();
        // 시드의 승인된 신청서(산골농부 박씨네)는 진행 중이 아니라 신청서 쪽 검사에는 걸리지 않는다 — 스토어 쪽 검사가 막음
        assertThat(storeApplicationRepository.existsByNormalizedStoreNameAndStatusIn("산골농부 박씨네", IN_PROGRESS)).isFalse();
    }

    @Test
    @DisplayName("자동 반려 대상 — 보완요청 시각이 기준 이전(포함)인 SUPPLEMENT 만, 오래된 순 · 개수 제한")
    void findExpiredSupplementIds_returnsOnlyExpiredSupplements() {
        // given — 기준 10/22 10:00
        LocalDateTime before = LocalDateTime.of(2026, 10, 22, 10, 0);
        Long onBoundary = saveWithStatus(41L, "경계상회", "boundary-store", "SUPPLEMENT", before);
        Long older = saveWithStatus(42L, "오래된상회", "older-store", "SUPPLEMENT", before.minusDays(1));
        saveWithStatus(43L, "기한전상회", "not-due-store", "SUPPLEMENT", before.plusSeconds(1));
        saveWithStatus(44L, "재제출상회", "pending-store", "PENDING", before.minusDays(3));

        // when · then — 시드 102(보완요청 중)는 최근이라 대상 아님
        assertThat(storeApplicationRepository.findExpiredSupplementIds(before, Limit.of(10))).containsExactly(older, onBoundary);
        assertThat(storeApplicationRepository.findExpiredSupplementIds(before, Limit.of(1))).containsExactly(older);
    }

    private Long saveWithStatus(Long userId, String storeName, String slug, String status, LocalDateTime statusChangedAt) {
        Long id = storeApplicationRepository.saveAndFlush(application(userId, storeName, slug, statusChangedAt)).getId();
        update("UPDATE store_application SET status = '" + status + "', status_changed_at = '" + statusChangedAt
                + "' WHERE store_application_id = " + id);
        return id;
    }

    @Test
    @DisplayName("재제출 주소 · 스토어명 중복 — 자기 신청은 빼고 다른 진행 중 신청과만 비교")
    void existsByStoreIdentityAndIdNot_excludesOwnApplication() {
        // given
        Long own = storeApplicationRepository.saveAndFlush(application(4L, "Linen Lab", "linen-lab", LocalDateTime.of(2026, 10, 22, 10, 0))).getId();
        Long other = storeApplicationRepository.saveAndFlush(application(7L, "토리 상점", "tori-shop", LocalDateTime.of(2026, 10, 23, 10, 0))).getId();

        // when · then — 자기 값은 겹침이 아니고, 다른 신청 값은 겹침
        assertThat(storeApplicationRepository.existsBySlugAndStatusInAndIdNot("linen-lab", IN_PROGRESS, own)).isFalse();
        assertThat(storeApplicationRepository.existsByNormalizedStoreNameAndStatusInAndIdNot("linenlab", IN_PROGRESS, own)).isFalse();
        assertThat(storeApplicationRepository.existsBySlugAndStatusInAndIdNot("tori-shop", IN_PROGRESS, own)).isTrue();
        assertThat(storeApplicationRepository.existsByNormalizedStoreNameAndStatusInAndIdNot("토리상점", IN_PROGRESS, own)).isTrue();
        assertThat(storeApplicationRepository.existsByNormalizedStoreNameAndStatusInAndIdNot("토리상점", IN_PROGRESS, other)).isFalse();
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
    @DisplayName("심사 목록은 제출 최신순이고, 제출 시각이 같으면 나중에 들어온 신청이 먼저다")
    void findAllOrderByTermsAgreedAt_sameSubmittedAt_newerIdFirst() {
        // given — 시드 신청서(모두 승인)보다 최근에 같은 시각으로 두 건, 그보다 앞서 한 건
        LocalDateTime submittedAt = LocalDateTime.of(2099, 1, 1, 10, 0);
        Long first = storeApplicationRepository.saveAndFlush(application(4L, "첫 스토어", "first-store", submittedAt)).getId();
        Long second = storeApplicationRepository.saveAndFlush(application(5L, "둘 스토어", "second-store", submittedAt)).getId();
        Long older = storeApplicationRepository.saveAndFlush(application(6L, "옛 스토어", "older-store", submittedAt.minusDays(1))).getId();

        // when
        List<StoreApplication> all = storeApplicationRepository.findAllByOrderByTermsAgreedAtDescIdDesc();

        // then
        assertThat(all).extracting(StoreApplication::getId).startsWith(second, first, older);
        assertThat(all).extracting(StoreApplication::getTermsAgreedAt)
                .isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    @DisplayName("상태 필터 목록은 그 상태의 신청만 돌려준다")
    void findAllByStatus_pendingFilter_returnsOnlyPending() {
        // given — 시드의 심사대기는 101(1일 전 제출) 한 건, 지금 제출한 새 신청이 그보다 먼저
        Long pending = storeApplicationRepository.saveAndFlush(application(4L, "새 스토어", "new-store", LocalDateTime.now())).getId();

        // when
        List<StoreApplication> pendings = storeApplicationRepository.findAllByStatusOrderByTermsAgreedAtDescIdDesc(ApplicationStatus.PENDING);
        List<StoreApplication> approved = storeApplicationRepository.findAllByStatusOrderByTermsAgreedAtDescIdDesc(ApplicationStatus.APPROVED);

        // then
        assertThat(pendings).extracting(StoreApplication::getId).containsExactly(pending, 101L);
        assertThat(approved).isNotEmpty().allMatch(a -> a.getStatus() == ApplicationStatus.APPROVED);
    }

    @Test
    @DisplayName("같은 사업자번호 조회는 이 신청을 빼고, 그 신청으로 만든 스토어는 소프트 삭제만 뺀다")
    void sameBizRegNo_excludesSelfAndSoftDeletedStore() {
        // given — 시드 신청 1(000-00-00006, 스토어 1) · 2(000-00-00004, 스토어 2) 와 같은 번호로 새 신청
        Long mine = storeApplicationRepository.saveAndFlush(application(4L, "새 스토어", "new-store", LocalDateTime.now())).getId();
        update("UPDATE store_application SET biz_reg_no = '000-00-00006' WHERE store_application_id IN (" + mine + ", 2)");
        update("UPDATE store SET status = 'CLOSED' WHERE store_id = 1");
        update("UPDATE store SET deleted_at = NOW(6) WHERE store_id = 2");

        // when
        List<StoreApplication> others = storeApplicationRepository.findAllByBizRegNoAndIdNotOrderByTermsAgreedAtDescIdDesc("000-00-00006", mine);
        List<Long> storeIds = storeRepository.findAllByStoreApplicationIdInAndDeletedAtIsNullOrderByIdAsc(
                others.stream().map(StoreApplication::getId).toList()).stream().map(Store::getId).toList();

        // then — 신청 2 가 1 보다 최근 제출
        assertThat(others).extracting(StoreApplication::getId).containsExactly(2L, 1L);
        assertThat(storeIds).containsExactly(1L);
        assertThat(storeRepository.findByStoreApplicationId(1L)).get().extracting(Store::getId).isEqualTo(1L);
        assertThat(storeRepository.findByStoreApplicationId(mine)).isEmpty();
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

    @Test
    @DisplayName("내 신청은 신청자의 가장 최근 신청 — 반려 뒤 다시 신청하면 새 신청, 신청 없는 회원은 empty")
    void findFirstByUserId_latestApplication_returnsNewest() {
        // given — user 7 이 반려된 옛 신청 뒤 새로 신청
        Long old = storeApplicationRepository.saveAndFlush(application(7L, "옛 스토어", "old-one", LocalDateTime.of(2026, 8, 1, 10, 0))).getId();
        update("UPDATE store_application SET status = 'REJECTED' WHERE store_application_id = " + old);
        Long latest = storeApplicationRepository.saveAndFlush(application(7L, "새 스토어", "new-one", LocalDateTime.of(2026, 10, 1, 10, 0))).getId();

        // when · then
        assertThat(storeApplicationRepository.findFirstByUserIdOrderByTermsAgreedAtDescIdDesc(7L))
                .get().extracting(StoreApplication::getId).isEqualTo(latest);
        assertThat(storeApplicationRepository.findFirstByUserIdOrderByTermsAgreedAtDescIdDesc(999L)).isEmpty();
        // 시드 — buyer2(user 5)는 보완요청 중인 102
        assertThat(storeApplicationRepository.findFirstByUserIdOrderByTermsAgreedAtDescIdDesc(5L))
                .get().extracting(StoreApplication::getStatus).isEqualTo(ApplicationStatus.SUPPLEMENT);
    }

    private void update(String sql) {
        entityManager.createNativeQuery(sql).executeUpdate();
        entityManager.clear();
    }
}
