package com.sesac.gojigi.store.apply.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.store.apply.client.SellerDocumentClient;
import com.sesac.gojigi.store.apply.client.SellerDocumentClient.PresignedDownload;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationDetailResponse;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationDetailResponse.History;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationDetailResponse.SameApplication;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationDetailResponse.SameStore;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationDetailResponse.SettlementAccount;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationSummaryResponse;
import com.sesac.gojigi.store.apply.dto.response.MyApplicationResponse;
import com.sesac.gojigi.store.apply.entity.ActorType;
import com.sesac.gojigi.store.apply.entity.ApplicationDocument;
import com.sesac.gojigi.store.apply.entity.ApplicationHistory;
import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.entity.BizType;
import com.sesac.gojigi.store.apply.entity.DocType;
import com.sesac.gojigi.store.apply.entity.ExpectedMonthlySales;
import com.sesac.gojigi.store.apply.entity.RepresentativeProduct;
import com.sesac.gojigi.store.apply.entity.StoreApplication;
import com.sesac.gojigi.store.apply.exception.ApplyErrorCode;
import com.sesac.gojigi.store.apply.repository.ApplicationDocumentRepository;
import com.sesac.gojigi.store.apply.repository.ApplicationHistoryRepository;
import com.sesac.gojigi.store.apply.repository.StoreApplicationRepository;
import com.sesac.gojigi.store.store.entity.Store;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.repository.StoreRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ApplicationQueryServiceTest {

    private static final LocalDateTime SUBMITTED_AT = LocalDateTime.of(2026, 10, 22, 14, 5, 11);

    @Mock
    private StoreApplicationRepository storeApplicationRepository;

    @Mock
    private ApplicationDocumentRepository applicationDocumentRepository;

    @Mock
    private ApplicationHistoryRepository applicationHistoryRepository;

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private SellerDocumentClient sellerDocumentClient;

    @InjectMocks
    private ApplicationQueryService applicationQueryService;

    @Test
    @DisplayName("목록 한 줄은 접수번호를 계산하고 제출 · 상태 변경 시각을 +09:00 으로 바꾼다")
    void getList_withStatus_mapsSummary() {
        // given
        StoreApplication application = mock(StoreApplication.class);
        given(application.getId()).willReturn(27L);
        given(application.getStoreName()).willReturn("곳간상회");
        given(application.getOwnerName()).willReturn("홍길동");
        given(application.getBizType()).willReturn(BizType.INDIVIDUAL);
        given(application.getCategoryId()).willReturn(1L);
        given(application.getExpectedMonthlySales()).willReturn(ExpectedMonthlySales.FROM_5M_TO_20M);
        given(application.getStatus()).willReturn(ApplicationStatus.SUPPLEMENT);
        given(application.getTermsAgreedAt()).willReturn(SUBMITTED_AT);
        given(application.getStatusChangedAt()).willReturn(LocalDateTime.of(2026, 10, 23, 10, 0));
        given(storeApplicationRepository.findAllByStatusOrderByTermsAgreedAtDescIdDesc(ApplicationStatus.SUPPLEMENT))
                .willReturn(List.of(application));

        // when
        List<InternalApplicationSummaryResponse> result = applicationQueryService.getList(ApplicationStatus.SUPPLEMENT);

        // then
        assertThat(result).containsExactly(new InternalApplicationSummaryResponse(27L, "AP-2610-027", "곳간상회", "홍길동",
                BizType.INDIVIDUAL, 1L, ExpectedMonthlySales.FROM_5M_TO_20M, ApplicationStatus.SUPPLEMENT,
                OffsetDateTime.parse("2026-10-22T14:05:11+09:00"), OffsetDateTime.parse("2026-10-23T10:00:00+09:00")));
        then(storeApplicationRepository).should(never()).findAllByOrderByTermsAgreedAtDescIdDesc();
    }

    @Test
    @DisplayName("status 가 null 이면 전체 조회 쿼리를 쓴다")
    void getList_whenStatusNull_queriesAll() {
        // given
        given(storeApplicationRepository.findAllByOrderByTermsAgreedAtDescIdDesc()).willReturn(List.of());

        // when
        List<InternalApplicationSummaryResponse> result = applicationQueryService.getList(null);

        // then
        assertThat(result).isEmpty();
        then(storeApplicationRepository).should(never()).findAllByStatusOrderByTermsAgreedAtDescIdDesc(null);
    }

    @Test
    @DisplayName("상세는 신청서 · 계좌 · 서류(종류별 · 올린 순서, 열람 주소) · 이력 · 같은 사업자번호의 신청 · 스토어를 담는다")
    void getDetail_whenApplicationExists_assemblesAll() {
        // given
        StoreApplication application = application(27L, "000-00-00001", SUBMITTED_AT);
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application));
        given(storeRepository.findByStoreApplicationId(27L)).willReturn(Optional.empty());
        // 저장 순서와 다르게 — 통신판매업(2) 이전본 · 사업자등록증(1) · 통신판매업 지금 것
        ApplicationDocument oldEcommerce = document(103L, DocType.ECOMMERCE_REG, "old.pdf", false);
        ApplicationDocument business = document(101L, DocType.BUSINESS_REG, "biz.pdf", true);
        ApplicationDocument newEcommerce = document(105L, DocType.ECOMMERCE_REG, "new.pdf", true);
        given(applicationDocumentRepository.findAllByStoreApplicationId(27L)).willReturn(List.of(newEcommerce, business, oldEcommerce));
        Instant expiresAt = Instant.parse("2026-10-26T06:10:00Z");
        given(sellerDocumentClient.presignDownload(any())).willAnswer(inv ->
                Optional.of(new PresignedDownload("https://docs/" + inv.getArgument(0), expiresAt)));
        given(applicationHistoryRepository.findAllByStoreApplicationIdOrderByIdAsc(27L)).willReturn(List.of(
                history(ApplicationStatus.PENDING, ActorType.APPLICANT, null, SUBMITTED_AT),
                history(ApplicationStatus.SUPPLEMENT, ActorType.ADMIN, "사본이 흐립니다", LocalDateTime.of(2026, 10, 23, 10, 0))));
        StoreApplication rejected = application(9L, "000-00-00001", LocalDateTime.of(2026, 9, 14, 11, 0));
        ReflectionTestUtils.setField(rejected, "status", ApplicationStatus.REJECTED);
        StoreApplication approved = application(3L, "000-00-00001", LocalDateTime.of(2026, 8, 1, 9, 0));
        given(storeApplicationRepository.findAllByBizRegNoAndIdNotOrderByTermsAgreedAtDescIdDesc("000-00-00001", 27L))
                .willReturn(List.of(rejected, approved));
        Store closedStore = mock(Store.class);
        given(closedStore.getId()).willReturn(5L);
        given(closedStore.getStoreName()).willReturn("곳간상점");
        given(closedStore.getStatus()).willReturn(StoreStatus.CLOSED);
        given(storeRepository.findAllByStoreApplicationIdInAndDeletedAtIsNullOrderByIdAsc(List.of(9L, 3L)))
                .willReturn(List.of(closedStore));

        // when
        InternalApplicationDetailResponse detail = applicationQueryService.getDetail(27L);

        // then
        assertThat(detail.applicationNo()).isEqualTo("AP-2610-027");
        assertThat(detail.status()).isEqualTo(ApplicationStatus.PENDING);
        assertThat(detail.submittedAt()).isEqualTo(OffsetDateTime.parse("2026-10-22T14:05:11+09:00"));
        assertThat(detail.storeId()).isNull();
        assertThat(detail.bizRegNo()).isEqualTo("000-00-00001");
        assertThat(detail.representativeProducts()).hasSize(2);
        assertThat(detail.settlementAccount()).isEqualTo(new SettlementAccount("국민", "000000-00-000000", "홍길동"));
        assertThat(detail.documents()).extracting(InternalApplicationDetailResponse.Document::documentId)
                .containsExactly(101L, 103L, 105L);
        assertThat(detail.documents()).extracting(InternalApplicationDetailResponse.Document::current)
                .containsExactly(true, false, true);
        assertThat(detail.documents().get(0).url()).isEqualTo("https://docs/applications/27/biz.pdf");
        assertThat(detail.documents().get(0).urlExpiresAt()).isEqualTo(OffsetDateTime.parse("2026-10-26T15:10:00+09:00"));
        assertThat(detail.documents().get(0).uploadedAt()).isEqualTo(OffsetDateTime.parse("2026-10-22T14:05:11+09:00"));
        assertThat(detail.history()).containsExactly(
                new History(null, ApplicationStatus.PENDING, ActorType.APPLICANT, null, OffsetDateTime.parse("2026-10-22T14:05:11+09:00")),
                new History(null, ApplicationStatus.SUPPLEMENT, ActorType.ADMIN, "사본이 흐립니다",
                        OffsetDateTime.parse("2026-10-23T10:00:00+09:00")));
        assertThat(detail.sameBizRegNo().applications()).containsExactly(
                new SameApplication(9L, "AP-2609-009", "스토어9", ApplicationStatus.REJECTED,
                        OffsetDateTime.parse("2026-09-14T11:00:00+09:00")),
                new SameApplication(3L, "AP-2608-003", "스토어3", ApplicationStatus.PENDING,
                        OffsetDateTime.parse("2026-08-01T09:00:00+09:00")));
        assertThat(detail.sameBizRegNo().stores()).containsExactly(new SameStore(5L, "곳간상점", StoreStatus.CLOSED));
    }

    @Test
    @DisplayName("승인된 신청은 만들어진 스토어 ID 를, 같은 사업자번호가 없으면 빈 목록을 — 스토어 조회도 하지 않는다")
    void getDetail_whenApprovedAndNoSameBizRegNo_returnsStoreIdAndEmptyLists() {
        // given
        given(storeApplicationRepository.findById(1L)).willReturn(Optional.of(application(1L, "000-00-00006", SUBMITTED_AT)));
        Store store = mock(Store.class);
        given(store.getId()).willReturn(1L);
        given(storeRepository.findByStoreApplicationId(1L)).willReturn(Optional.of(store));
        given(applicationDocumentRepository.findAllByStoreApplicationId(1L)).willReturn(List.of());
        given(applicationHistoryRepository.findAllByStoreApplicationIdOrderByIdAsc(1L)).willReturn(List.of());
        given(storeApplicationRepository.findAllByBizRegNoAndIdNotOrderByTermsAgreedAtDescIdDesc("000-00-00006", 1L))
                .willReturn(List.of());

        // when
        InternalApplicationDetailResponse detail = applicationQueryService.getDetail(1L);

        // then
        assertThat(detail.storeId()).isEqualTo(1L);
        assertThat(detail.documents()).isEmpty();
        assertThat(detail.sameBizRegNo().applications()).isEmpty();
        assertThat(detail.sameBizRegNo().stores()).isEmpty();
        then(storeRepository).should(never()).findAllByStoreApplicationIdInAndDeletedAtIsNullOrderByIdAsc(anyCollection());
    }

    @Test
    @DisplayName("열람 주소 발급이 실패하면 상세는 그대로 주고 url 은 null — 첫 실패 뒤 나머지 서류는 서명을 시도하지 않는다")
    void getDetail_whenPresignFails_returnsNullUrlsAndStops() {
        // given
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application(27L, "000-00-00001", SUBMITTED_AT)));
        given(storeRepository.findByStoreApplicationId(27L)).willReturn(Optional.empty());
        given(applicationDocumentRepository.findAllByStoreApplicationId(27L)).willReturn(List.of(
                document(101L, DocType.BUSINESS_REG, "biz.pdf", true),
                document(102L, DocType.BANK_COPY, "bank.pdf", true)));
        given(sellerDocumentClient.presignDownload(any())).willReturn(Optional.empty());
        given(applicationHistoryRepository.findAllByStoreApplicationIdOrderByIdAsc(27L)).willReturn(List.of());
        given(storeApplicationRepository.findAllByBizRegNoAndIdNotOrderByTermsAgreedAtDescIdDesc("000-00-00001", 27L))
                .willReturn(List.of());

        // when
        InternalApplicationDetailResponse detail = applicationQueryService.getDetail(27L);

        // then
        assertThat(detail.documents()).hasSize(2).allSatisfy(d -> {
            assertThat(d.url()).isNull();
            assertThat(d.urlExpiresAt()).isNull();
        });
        then(sellerDocumentClient).should(times(1)).presignDownload(any());
    }

    @Test
    @DisplayName("없는 신청은 APPLY_NOT_FOUND")
    void getDetail_whenApplicationMissing_throwsNotFound() {
        // given
        given(storeApplicationRepository.findById(99L)).willReturn(Optional.empty());

        // when · then
        assertThatThrownBy(() -> applicationQueryService.getDetail(99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ApplyErrorCode.APPLY_NOT_FOUND);
    }

    private StoreApplication application(Long id, String bizRegNo, LocalDateTime submittedAt) {
        StoreApplication application = StoreApplication.builder()
                .userId(4L).categoryId(1L).bizType(BizType.INDIVIDUAL)
                .companyName("곳간상회").ownerName("홍길동").bizRegNo(bizRegNo).ecommerceRegNo("2026-서울성동-0000")
                .managerPhone("010-0000-1234").managerEmail("seller@example.com").businessAddress("서울시 성동구")
                .storeName("스토어" + id).slug("store-" + id).intro("<p>소개</p>")
                .representativeProducts(List.of(
                        new RepresentativeProduct("리넨 셔츠", "셔츠", 39000),
                        new RepresentativeProduct("리넨 팬츠", "팬츠", 45000)))
                .expectedMonthlySales(ExpectedMonthlySales.FROM_5M_TO_20M)
                .bankName("국민").accountNo("000000-00-000000").accountHolder("홍길동")
                .marketingAgreed(false).submittedAt(submittedAt)
                .build();
        ReflectionTestUtils.setField(application, "id", id);
        return application;
    }

    private ApplicationDocument document(Long id, DocType docType, String fileName, boolean current) {
        ApplicationDocument document = ApplicationDocument.of(27L, docType, "applications/27/" + fileName, fileName,
                "application/pdf", 1234);
        ReflectionTestUtils.setField(document, "id", id);
        ReflectionTestUtils.setField(document, "current", current);
        ReflectionTestUtils.setField(document, "createdAt", SUBMITTED_AT);
        return document;
    }

    private ApplicationHistory history(ApplicationStatus toStatus, ActorType actor, String reason, LocalDateTime at) {
        ApplicationHistory history = ApplicationHistory.of(27L, null, toStatus, actor, reason);
        ReflectionTestUtils.setField(history, "createdAt", at);
        return history;
    }

    @Test
    @DisplayName("내 신청 — 보완요청이면 사유 · 보완 기한(+7일) · 이력, 반려 · 승인 값은 null")
    void getMine_whenSupplement_returnsDueAtAndHistory() {
        // given
        StoreApplication application = application(27L, "000-00-00001", SUBMITTED_AT);
        application.requestSupplement("사본이 흐립니다", LocalDateTime.of(2026, 10, 23, 10, 0));
        given(storeApplicationRepository.findFirstByUserIdOrderByTermsAgreedAtDescIdDesc(4L)).willReturn(Optional.of(application));
        given(applicationHistoryRepository.findAllByStoreApplicationIdOrderByIdAsc(27L)).willReturn(List.of(
                history(ApplicationStatus.PENDING, ActorType.APPLICANT, null, SUBMITTED_AT),
                history(ApplicationStatus.SUPPLEMENT, ActorType.ADMIN, "사본이 흐립니다", LocalDateTime.of(2026, 10, 23, 10, 0))));

        // when
        MyApplicationResponse mine = applicationQueryService.getMine(4L);

        // then
        assertThat(mine.applicationNo()).isEqualTo("AP-2610-027");
        assertThat(mine.status()).isEqualTo(ApplicationStatus.SUPPLEMENT);
        assertThat(mine.statusReason()).isEqualTo("사본이 흐립니다");
        assertThat(mine.supplementDueAt()).isEqualTo(OffsetDateTime.parse("2026-10-30T10:00:00+09:00"));
        assertThat(mine.reapplyAvailableAt()).isNull();
        assertThat(mine.storeId()).isNull();
        assertThat(mine.history()).extracting(History::toStatus).containsExactly(ApplicationStatus.PENDING, ApplicationStatus.SUPPLEMENT);
        then(storeRepository).should(never()).findByStoreApplicationId(any());
    }

    @Test
    @DisplayName("내 신청 — 반려면 재신청 가능 시각(반려 + 30일), 승인이면 만들어진 storeId")
    void getMine_whenRejectedOrApproved_returnsStatusSpecificValues() {
        // given
        StoreApplication rejected = application(27L, "000-00-00001", SUBMITTED_AT);
        rejected.reject("서류 불일치", LocalDateTime.of(2026, 10, 24, 9, 0));
        StoreApplication approved = application(28L, "000-00-00002", SUBMITTED_AT);
        approved.approve(LocalDateTime.of(2026, 10, 24, 9, 0));
        given(storeApplicationRepository.findFirstByUserIdOrderByTermsAgreedAtDescIdDesc(4L)).willReturn(Optional.of(rejected));
        given(storeApplicationRepository.findFirstByUserIdOrderByTermsAgreedAtDescIdDesc(5L)).willReturn(Optional.of(approved));
        Store store = mock(Store.class);
        given(store.getId()).willReturn(3L);
        given(storeRepository.findByStoreApplicationId(28L)).willReturn(Optional.of(store));

        // when
        MyApplicationResponse mineRejected = applicationQueryService.getMine(4L);
        MyApplicationResponse mineApproved = applicationQueryService.getMine(5L);

        // then
        assertThat(mineRejected.reapplyAvailableAt()).isEqualTo(OffsetDateTime.parse("2026-11-23T09:00:00+09:00"));
        assertThat(mineRejected.supplementDueAt()).isNull();
        assertThat(mineRejected.statusReason()).isEqualTo("서류 불일치");
        assertThat(mineApproved.storeId()).isEqualTo(3L);
        assertThat(mineApproved.reapplyAvailableAt()).isNull();
    }

    @Test
    @DisplayName("신청한 적이 없으면 null")
    void getMine_whenNeverApplied_returnsNull() {
        // given
        given(storeApplicationRepository.findFirstByUserIdOrderByTermsAgreedAtDescIdDesc(6L)).willReturn(Optional.empty());

        // when · then
        assertThat(applicationQueryService.getMine(6L)).isNull();
    }
}
