package com.sesac.gojigi.store.apply.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.store.TestcontainersConfiguration;
import com.sesac.gojigi.store.apply.entity.ActorType;
import com.sesac.gojigi.store.apply.entity.ApplicationHistory;
import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.entity.BizType;
import com.sesac.gojigi.store.apply.entity.RepresentativeProduct;
import com.sesac.gojigi.store.apply.entity.StoreApplication;
import com.sesac.gojigi.store.apply.event.StoreApprovedPublisher;
import com.sesac.gojigi.store.apply.exception.ApplyErrorCode;
import com.sesac.gojigi.store.apply.repository.ApplicationHistoryRepository;
import com.sesac.gojigi.store.apply.repository.StoreApplicationRepository;
import com.sesac.gojigi.store.common.event.SnsEventPublisher;
import com.sesac.gojigi.store.store.entity.Store;
import com.sesac.gojigi.store.store.entity.StoreActorType;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.entity.StoreStatusHistory;
import com.sesac.gojigi.store.store.repository.StoreRepository;
import com.sesac.gojigi.store.store.repository.StoreStatusHistoryRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.context.transaction.TestTransaction;
import tools.jackson.databind.json.JsonMapper;

/** 실제 MySQL + Flyway 위에서 승인 저장 결과 · UNIQUE 위반 메시지(제약 이름) · 커밋 뒤에만 발행되는지 확인 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, ApplicationReviewService.class, StoreApprovedPublisher.class,
        ApplicationReviewServiceIntegrationTest.FixedClock.class})
@ActiveProfiles("test")
class ApplicationReviewServiceIntegrationTest {

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
    private ApplicationReviewService service;
    @Autowired
    private StoreApplicationRepository storeApplicationRepository;
    @Autowired
    private ApplicationHistoryRepository applicationHistoryRepository;
    @Autowired
    private StoreRepository storeRepository;
    @Autowired
    private StoreStatusHistoryRepository storeStatusHistoryRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("승인하면 스토어 · 스토어 이력 · 신청 상태 · 신청 이력이 DB 에 저장되고, 매핑 안 한 컬럼은 DDL 기본값이 들어간다")
    void approve_whenPending_persistsStoreAndHistories() {
        // given
        StoreApplication application = storeApplicationRepository.saveAndFlush(application(4L, "곳간상회", "gotgan"));
        long versionBefore = application.getVersion();

        // when
        Long storeId = service.approve(application.getId()).storeId();
        entityManager.clear();

        // then
        Store store = storeRepository.findById(storeId).orElseThrow();
        assertThat(store.getStoreApplicationId()).isEqualTo(application.getId());
        assertThat(store.getCsPhone()).isEqualTo("010-0000-1234");
        assertThat(store.getIntro()).isEqualTo("<p>소개</p>");
        assertThat(store.getStatus()).isEqualTo(StoreStatus.ACTIVE);
        assertThat(store.getShippingFee()).isEqualTo(3000L);
        Map<String, Object> defaults = jdbcTemplate.queryForMap("SELECT color, grade FROM store WHERE store_id = ?", storeId);
        assertThat(defaults).containsEntry("color", "#5b6b4a").containsEntry("grade", "SPROUT");

        StoreApplication approved = storeApplicationRepository.findById(application.getId()).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(approved.getStatusChangedAt()).isEqualTo(NOW);
        assertThat(approved.getVersion()).isEqualTo(versionBefore + 1);

        List<StoreStatusHistory> storeHistories = storeStatusHistoryRepository.findAllByStoreIdOrderByIdAsc(storeId);
        assertThat(storeHistories).singleElement().satisfies(h -> {
            assertThat(h.getFromStatus()).isNull();
            assertThat(h.getToStatus()).isEqualTo(StoreStatus.ACTIVE);
            assertThat(h.getActorType()).isEqualTo(StoreActorType.ADMIN);
        });
        List<ApplicationHistory> applicationHistories =
                applicationHistoryRepository.findAllByStoreApplicationIdOrderByIdAsc(application.getId());
        assertThat(applicationHistories).singleElement().satisfies(h -> {
            assertThat(h.getFromStatus()).isEqualTo(ApplicationStatus.PENDING);
            assertThat(h.getToStatus()).isEqualTo(ApplicationStatus.APPROVED);
            assertThat(h.getActorType()).isEqualTo(ActorType.ADMIN);
        });
    }

    @Test
    @DisplayName("MySQL 의 UNIQUE 위반 메시지에 제약 이름이 들어 있어 스토어 주소 중복을 409 코드로 고를 수 있다")
    void duplicateCode_whenRealSlugViolation_mapsToSlugDuplicated() {
        // given — 미리 검사를 건너뛰고 같은 slug 스토어 두 개를 직접 저장
        StoreApplication first = storeApplicationRepository.saveAndFlush(application(4L, "곳간상회", "gotgan"));
        StoreApplication second = storeApplicationRepository.saveAndFlush(application(5L, "다른상회", "gotgan"));
        storeRepository.saveAndFlush(Store.open(first.getId(), 4L, "곳간상회", "gotgan", null, "010-0000-1234"));

        // when
        DataIntegrityViolationException e = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> storeRepository.saveAndFlush(Store.open(second.getId(), 5L, "다른상회", "gotgan", null, "010-0000-5678")));

        // then
        assertThat(ApplicationReviewService.duplicateCode(e)).isEqualTo(ApplyErrorCode.APPLY_SLUG_DUPLICATED);
    }

    @Test
    @DisplayName("승인이 롤백되면 StoreApproved 를 보내지 않는다")
    void approve_whenRolledBack_doesNotPublish() {
        // given
        StoreApplication application = storeApplicationRepository.saveAndFlush(application(4L, "곳간상회", "gotgan"));
        service.approve(application.getId());

        // when
        TestTransaction.flagForRollback();
        TestTransaction.end();

        // then
        verify(snsEventPublisher, never()).send(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("승인이 커밋된 뒤에 StoreApproved 를 한 번 보낸다")
    void approve_whenCommitted_publishesOnce() {
        // given
        StoreApplication application = storeApplicationRepository.saveAndFlush(application(9L, "커밋상회", "commit-store"));
        Long storeId = service.approve(application.getId()).storeId();
        verify(snsEventPublisher, never()).send(anyString(), anyString(), anyString(), anyString()); // 커밋 전에는 안 보냄

        // when
        TestTransaction.flagForCommit();
        TestTransaction.end();

        // then
        try {
            verify(snsEventPublisher).send(eq("StoreApproved"), eq(String.valueOf(storeId)), anyString(), anyString());
        } finally { // 커밋된 행은 다른 테스트에 남지 않게 지운다 (FK 역순)
            jdbcTemplate.update("DELETE FROM store_status_history WHERE store_id = ?", storeId);
            jdbcTemplate.update("DELETE FROM store WHERE store_id = ?", storeId);
            jdbcTemplate.update("DELETE FROM application_history WHERE store_application_id = ?", application.getId());
            jdbcTemplate.update("DELETE FROM store_application WHERE store_application_id = ?", application.getId());
        }
    }

    private static StoreApplication application(Long userId, String storeName, String slug) {
        return StoreApplication.builder()
                .userId(userId).categoryId(1L).bizType(BizType.INDIVIDUAL)
                .companyName(storeName).ownerName("홍길동").bizRegNo("000-00-00001").ecommerceRegNo("2026-서울성동-0000")
                .managerPhone("010-0000-1234").managerEmail("seller@example.com").businessAddress("서울시 성동구")
                .storeName(storeName).slug(slug).intro("<p>소개</p>")
                .representativeProducts(List.of(
                        new RepresentativeProduct("리넨 셔츠", "셔츠", 39000),
                        new RepresentativeProduct("리넨 팬츠", "팬츠", 45000)))
                .bankName("국민").accountNo("000000-00-000000").accountHolder("홍길동")
                .marketingAgreed(false).submittedAt(LocalDateTime.of(2026, 10, 27, 10, 0))
                .build();
    }
}
