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
import com.sesac.gojigi.store.apply.entity.DocType;
import com.sesac.gojigi.store.apply.entity.RepresentativeProduct;
import com.sesac.gojigi.store.apply.entity.StoreApplication;
import com.sesac.gojigi.store.apply.exception.ApplyErrorCode;
import com.sesac.gojigi.store.apply.repository.ApplicationHistoryRepository;
import com.sesac.gojigi.store.apply.repository.StoreApplicationRepository;
import com.sesac.gojigi.store.common.event.EventOutboxService;
import com.sesac.gojigi.store.common.event.OutboxEvent;
import com.sesac.gojigi.store.common.event.OutboxEventRepository;
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
import org.mockito.ArgumentCaptor;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 MySQL + Flyway 위에서 승인 저장 결과 · UNIQUE 위반 메시지(제약 이름) · 아웃박스 행 · 커밋 뒤에만 발행되는지 확인 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, ApplicationReviewService.class, EventOutboxService.class, SupplementDeadline.class,
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
    @Autowired
    private OutboxEventRepository outboxEventRepository;

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
    @DisplayName("승인이 롤백되면 아웃박스 행도 남지 않고 StoreApproved 를 보내지 않는다")
    void approve_whenRolledBack_leavesNoOutboxRow() {
        // given
        StoreApplication application = storeApplicationRepository.saveAndFlush(application(4L, "곳간상회", "gotgan"));
        service.approve(application.getId());
        assertThat(outboxEventRepository.count()).isEqualTo(1); // 같은 트랜잭션 안에서는 보임

        // when
        TestTransaction.flagForRollback();
        TestTransaction.end();

        // then
        assertThat(outboxEventRepository.count()).isZero();
        verify(snsEventPublisher, never()).send(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("승인이 커밋되면 직후 StoreApproved 봉투를 한 번 보내고 아웃박스 행은 SENT — occurredAt = 승인 시각, payload 는 events.md 이름 그대로")
    void approve_whenCommitted_publishesEnvelopeOnce() {
        // given
        StoreApplication application = storeApplicationRepository.saveAndFlush(application(9L, "커밋상회", "commit-store"));
        Long storeId = service.approve(application.getId()).storeId();
        verify(snsEventPublisher, never()).send(anyString(), anyString(), anyString(), anyString()); // 커밋 전에는 안 보냄

        // when
        TestTransaction.flagForCommit();
        TestTransaction.end();

        // then
        try {
            String eventId = ApplicationReviewService.storeApprovedEventId(application.getId());
            ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
            verify(snsEventPublisher).send(eq("StoreApproved"), eq(String.valueOf(storeId)), eq(eventId), json.capture());
            JsonNode envelope = JsonMapper.builder().build().readTree(json.getValue());
            assertThat(envelope.get("eventId").asString()).isEqualTo(eventId);
            assertThat(envelope.get("occurredAt").asString()).isEqualTo("2026-10-28T14:30:00+09:00");
            assertThat(envelope.get("aggregateId").asString()).isEqualTo(String.valueOf(storeId));
            JsonNode payload = envelope.get("payload");
            assertThat(payload.get("storeId").asLong()).isEqualTo(storeId);
            assertThat(payload.get("userId").asLong()).isEqualTo(9L);
            assertThat(payload.get("storeName").asString()).isEqualTo("커밋상회");
            assertThat(payload.get("sellerEmail").asString()).isEqualTo("seller@example.com");
            assertThat(payload.get("settlementAccount").get("bank").asString()).isEqualTo("국민");
            assertThat(payload.get("settlementAccount").get("number").asString()).isEqualTo("000000-00-000000");
            assertThat(payload.get("settlementAccount").get("holder").asString()).isEqualTo("홍길동");
            assertThat(outboxEventRepository.findAll()).singleElement()
                    .satisfies(row -> assertThat(row.getStatus()).isEqualTo(OutboxEvent.Status.SENT));
        } finally { // 커밋된 행은 다른 테스트에 남지 않게 지운다 (FK 역순)
            jdbcTemplate.update("DELETE FROM outbox_event");
            jdbcTemplate.update("DELETE FROM store_status_history WHERE store_id = ?", storeId);
            jdbcTemplate.update("DELETE FROM store WHERE store_id = ?", storeId);
            jdbcTemplate.update("DELETE FROM application_history WHERE store_application_id = ?", application.getId());
            jdbcTemplate.update("DELETE FROM store_application WHERE store_application_id = ?", application.getId());
        }
    }

    @Test
    @DisplayName("반려하면 신청 상태 · 사유 · 이력이 저장되고, 30일 재신청 제한 조회가 이 신청을 가장 최근 반려로 찾는다")
    void reject_whenPending_persistsStatusReasonAndHistory() {
        // given
        StoreApplication application = storeApplicationRepository.saveAndFlush(application(4L, "곳간상회", "gotgan"));
        long versionBefore = application.getVersion();

        // when
        service.reject(application.getId(), "사업자등록증 정보가 신청서와 다릅니다");
        entityManager.clear();

        // then
        StoreApplication rejected = storeApplicationRepository.findById(application.getId()).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(rejected.getStatusReason()).isEqualTo("사업자등록증 정보가 신청서와 다릅니다");
        assertThat(rejected.getStatusChangedAt()).isEqualTo(NOW);
        assertThat(rejected.getVersion()).isEqualTo(versionBefore + 1);
        assertThat(applicationHistoryRepository.findAllByStoreApplicationIdOrderByIdAsc(application.getId()))
                .singleElement().satisfies(h -> {
                    assertThat(h.getFromStatus()).isEqualTo(ApplicationStatus.PENDING);
                    assertThat(h.getToStatus()).isEqualTo(ApplicationStatus.REJECTED);
                    assertThat(h.getActorType()).isEqualTo(ActorType.ADMIN);
                    assertThat(h.getReason()).isEqualTo("사업자등록증 정보가 신청서와 다릅니다");
                });
        assertThat(storeApplicationRepository.findFirstByUserIdAndStatusOrderByStatusChangedAtDesc(4L, ApplicationStatus.REJECTED))
                .get().extracting(StoreApplication::getId).isEqualTo(application.getId());
    }

    @Test
    @DisplayName("반려가 커밋되면 직후 StoreApplicationRejected 봉투를 한 번 보낸다 — aggregateId = 신청 ID, decidedAt 은 +09:00 문자열")
    void reject_whenCommitted_publishesEnvelopeOnce() {
        // given
        StoreApplication application = storeApplicationRepository.saveAndFlush(application(9L, "반려상회", "reject-store"));
        service.reject(application.getId(), "통신판매업 신고번호를 확인할 수 없습니다");
        verify(snsEventPublisher, never()).send(anyString(), anyString(), anyString(), anyString());

        // when
        TestTransaction.flagForCommit();
        TestTransaction.end();

        // then
        try {
            String eventId = ApplicationReviewService.applicationEventId("StoreApplicationRejected", application.getId());
            ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
            verify(snsEventPublisher).send(eq("StoreApplicationRejected"), eq(String.valueOf(application.getId())),
                    eq(eventId), json.capture());
            JsonNode envelope = JsonMapper.builder().build().readTree(json.getValue());
            assertThat(envelope.get("occurredAt").asString()).isEqualTo("2026-10-28T14:30:00+09:00");
            JsonNode payload = envelope.get("payload");
            assertThat(payload.get("applicationId").asLong()).isEqualTo(application.getId());
            assertThat(payload.get("userId").asLong()).isEqualTo(9L);
            assertThat(payload.get("storeName").asString()).isEqualTo("반려상회");
            assertThat(payload.get("sellerEmail").asString()).isEqualTo("seller@example.com");
            assertThat(payload.get("reason").asString()).isEqualTo("통신판매업 신고번호를 확인할 수 없습니다");
            // 알림 Lambda plusDays 가 앞 10자리(YYYY-MM-DD)를 읽는다
            assertThat(payload.get("decidedAt").asString()).isEqualTo("2026-10-28T14:30:00+09:00");
            assertThat(outboxEventRepository.findAll()).singleElement()
                    .satisfies(row -> assertThat(row.getStatus()).isEqualTo(OutboxEvent.Status.SENT));
        } finally { // 커밋된 행은 다른 테스트에 남지 않게 지운다 (FK 역순)
            jdbcTemplate.update("DELETE FROM outbox_event");
            jdbcTemplate.update("DELETE FROM application_history WHERE store_application_id = ?", application.getId());
            jdbcTemplate.update("DELETE FROM store_application WHERE store_application_id = ?", application.getId());
        }
    }

    @Test
    @DisplayName("보완요청이 커밋되면 신청 SUPPLEMENT · 이력이 저장되고 봉투를 한 번 보낸다 — 서류는 한글 이름, dueAt 은 +7일 +09:00 문자열")
    void requestSupplement_whenCommitted_persistsAndPublishesEnvelope() {
        // given
        StoreApplication application = storeApplicationRepository.saveAndFlush(application(9L, "보완상회", "supplement-store"));
        service.requestSupplement(application.getId(), "사본이 흐려 확인이 어렵습니다",
                List.of(DocType.BUSINESS_REG, DocType.BANK_COPY));
        verify(snsEventPublisher, never()).send(anyString(), anyString(), anyString(), anyString());

        // when
        TestTransaction.flagForCommit();
        TestTransaction.end();

        // then
        try {
            StoreApplication supplemented = storeApplicationRepository.findById(application.getId()).orElseThrow();
            assertThat(supplemented.getStatus()).isEqualTo(ApplicationStatus.SUPPLEMENT);
            assertThat(supplemented.getStatusReason()).isEqualTo("사본이 흐려 확인이 어렵습니다");
            assertThat(supplemented.getStatusChangedAt()).isEqualTo(NOW);
            assertThat(supplemented.getRequestedDocs()).containsExactly(DocType.BUSINESS_REG, DocType.BANK_COPY);   // V5 JSON
            assertThat(applicationHistoryRepository.findAllByStoreApplicationIdOrderByIdAsc(application.getId()))
                    .singleElement().satisfies(h -> {
                        assertThat(h.getToStatus()).isEqualTo(ApplicationStatus.SUPPLEMENT);
                        assertThat(h.getReason()).isEqualTo("사본이 흐려 확인이 어렵습니다");
                    });

            ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
            verify(snsEventPublisher).send(eq("StoreApplicationSupplementRequested"), eq(String.valueOf(application.getId())),
                    anyString(), json.capture());
            JsonNode payload = JsonMapper.builder().build().readTree(json.getValue()).get("payload");
            assertThat(payload.get("applicationId").asLong()).isEqualTo(application.getId());
            assertThat(payload.get("userId").asLong()).isEqualTo(9L);
            assertThat(payload.get("storeName").asString()).isEqualTo("보완상회");
            assertThat(payload.get("sellerEmail").asString()).isEqualTo("seller@example.com");
            assertThat(payload.get("requestedDocs").get(0).asString()).isEqualTo("사업자등록증");
            assertThat(payload.get("requestedDocs").get(1).asString()).isEqualTo("통장 사본");
            assertThat(payload.get("message").asString()).isEqualTo("사본이 흐려 확인이 어렵습니다");
            assertThat(payload.get("dueAt").asString()).isEqualTo("2026-11-04T14:30:00+09:00");
        } finally { // 커밋된 행은 다른 테스트에 남지 않게 지운다 (FK 역순)
            jdbcTemplate.update("DELETE FROM outbox_event");
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
