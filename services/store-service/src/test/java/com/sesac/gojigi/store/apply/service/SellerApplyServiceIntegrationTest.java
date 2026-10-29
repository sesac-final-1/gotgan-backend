package com.sesac.gojigi.store.apply.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.sesac.gojigi.store.TestcontainersConfiguration;
import com.sesac.gojigi.store.apply.client.SellerDocumentClient;
import com.sesac.gojigi.store.apply.client.SellerDocumentClient.FileMeta;
import com.sesac.gojigi.store.apply.dto.request.ApplicationResubmitRequest;
import com.sesac.gojigi.store.apply.dto.request.SellerApplyRequest.DocumentItem;
import com.sesac.gojigi.store.apply.entity.ActorType;
import com.sesac.gojigi.store.apply.entity.ApplicationDocument;
import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.entity.BizType;
import com.sesac.gojigi.store.apply.entity.DocType;
import com.sesac.gojigi.store.apply.entity.RepresentativeProduct;
import com.sesac.gojigi.store.apply.entity.StoreApplication;
import com.sesac.gojigi.store.apply.repository.ApplicationDocumentRepository;
import com.sesac.gojigi.store.apply.repository.ApplicationHistoryRepository;
import com.sesac.gojigi.store.apply.repository.StoreApplicationRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 실제 MySQL + Flyway(V5 requested_docs) 위에서 보완 재제출 — 서류 교체(is_current) · 고친 항목 · 요청 서류 비움 · 이력 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, SellerApplyService.class, SellerApplyServiceIntegrationTest.FixedClock.class})
@ActiveProfiles("test")
class SellerApplyServiceIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 16, 20);

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW.atZone(KST).toInstant(), KST);
        }
    }

    @MockitoBean
    private SellerDocumentClient sellerDocumentClient;

    @Autowired
    private SellerApplyService sellerApplyService;
    @Autowired
    private StoreApplicationRepository storeApplicationRepository;
    @Autowired
    private ApplicationDocumentRepository applicationDocumentRepository;
    @Autowired
    private ApplicationHistoryRepository applicationHistoryRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("재제출 — 요청 서류만 이전 제출본으로 바뀌고 새 행 추가, 다른 서류 · 안 보낸 항목은 그대로, requested_docs 는 NULL")
    void resubmit_persistsDocumentsFieldsAndHistory() {
        // given — 하루 전 통신판매업 신고증 보완요청, 서류는 사업자등록증 · 신고증(옛것)
        StoreApplication application = application();
        application.requestSupplement("신고증 사본이 흐립니다", List.of(DocType.ECOMMERCE_REG), NOW.minusDays(1));
        application = storeApplicationRepository.saveAndFlush(application);
        Long id = application.getId();
        applicationDocumentRepository.saveAllAndFlush(List.of(
                ApplicationDocument.of(id, DocType.BUSINESS_REG, "applications/41/biz.pdf", "biz.pdf", "application/pdf", 100),
                ApplicationDocument.of(id, DocType.ECOMMERCE_REG, "applications/41/ecom.pdf", "ecom.pdf", "application/pdf", 100)));
        given(sellerDocumentClient.head(anyString())).willReturn(Optional.of(new FileMeta("application/pdf", 300_000)));

        // when
        sellerApplyService.resubmit(41L, id, new ApplicationResubmitRequest("새상호", null, null, null, null, null, null, null, null,
                List.of(new DocumentItem(DocType.ECOMMERCE_REG, "applications/41/ecom2.pdf", "신고증.pdf"))));
        entityManager.flush();
        entityManager.clear();

        // then
        StoreApplication saved = storeApplicationRepository.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ApplicationStatus.PENDING);
        assertThat(saved.getStatusReason()).isNull();
        assertThat(saved.getStatusChangedAt()).isEqualTo(NOW);
        assertThat(saved.getCompanyName()).isEqualTo("새상호");
        assertThat(saved.getBankName()).isEqualTo("국민");
        assertThat(jdbcTemplate.queryForObject("SELECT requested_docs IS NULL FROM store_application WHERE store_application_id = ?",
                Boolean.class, id)).isTrue();

        assertThat(applicationDocumentRepository.findAllByStoreApplicationId(id))
                .extracting(ApplicationDocument::getS3Key, ApplicationDocument::isCurrent)
                .containsExactlyInAnyOrder(
                        tuple("applications/41/biz.pdf", true),
                        tuple("applications/41/ecom.pdf", false),
                        tuple("applications/41/ecom2.pdf", true));

        assertThat(applicationHistoryRepository.findAllByStoreApplicationIdOrderByIdAsc(id)).singleElement().satisfies(h -> {
            assertThat(h.getFromStatus()).isEqualTo(ApplicationStatus.SUPPLEMENT);
            assertThat(h.getToStatus()).isEqualTo(ApplicationStatus.PENDING);
            assertThat(h.getActorType()).isEqualTo(ActorType.APPLICANT);
            assertThat(h.getReason()).isEqualTo("재제출 — 서류: 통신판매업 신고증 / 수정: 상호");
        });
    }

    private static StoreApplication application() {
        return StoreApplication.builder()
                .userId(41L).categoryId(1L).bizType(BizType.INDIVIDUAL)
                .companyName("재제출상회").ownerName("홍길동").bizRegNo("000-00-00041").ecommerceRegNo("2026-서울성동-0000")
                .managerPhone("010-0000-1234").managerEmail("seller@example.com").businessAddress("서울시 성동구")
                .storeName("재제출상회").slug("resubmit-store").intro("<p>소개</p>")
                .representativeProducts(List.of(
                        new RepresentativeProduct("리넨 셔츠", "셔츠", 39000),
                        new RepresentativeProduct("리넨 팬츠", "팬츠", 45000)))
                .bankName("국민").accountNo("000000-00-000000").accountHolder("홍길동")
                .marketingAgreed(false).submittedAt(NOW.minusDays(3))
                .build();
    }
}
