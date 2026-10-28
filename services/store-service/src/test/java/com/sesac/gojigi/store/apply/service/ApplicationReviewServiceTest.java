package com.sesac.gojigi.store.apply.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.store.apply.dto.event.StoreApprovedPayload;
import com.sesac.gojigi.store.apply.dto.response.ApplicationApproveResponse;
import com.sesac.gojigi.store.apply.entity.ActorType;
import com.sesac.gojigi.store.apply.entity.ApplicationHistory;
import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.entity.BizType;
import com.sesac.gojigi.store.apply.entity.RepresentativeProduct;
import com.sesac.gojigi.store.apply.entity.StoreApplication;
import com.sesac.gojigi.store.apply.exception.ApplyErrorCode;
import com.sesac.gojigi.store.apply.repository.ApplicationHistoryRepository;
import com.sesac.gojigi.store.apply.repository.StoreApplicationRepository;
import com.sesac.gojigi.store.common.event.EventOutboxService;
import com.sesac.gojigi.store.store.entity.Store;
import com.sesac.gojigi.store.store.entity.StoreActorType;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.entity.StoreStatusHistory;
import com.sesac.gojigi.store.store.repository.StoreRepository;
import com.sesac.gojigi.store.store.repository.StoreStatusHistoryRepository;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ApplicationReviewServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 14, 30);

    @Mock
    private StoreApplicationRepository storeApplicationRepository;
    @Mock
    private ApplicationHistoryRepository applicationHistoryRepository;
    @Mock
    private StoreRepository storeRepository;
    @Mock
    private StoreStatusHistoryRepository storeStatusHistoryRepository;
    @Mock
    private EventOutboxService eventOutboxService;

    private ApplicationReviewService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(KST).toInstant(), KST);
        service = new ApplicationReviewService(storeApplicationRepository, applicationHistoryRepository,
                storeRepository, storeStatusHistoryRepository, eventOutboxService, clock);
    }

    @ParameterizedTest
    @CsvSource({"PENDING", "SUPPLEMENT"})
    @DisplayName("승인하면 신청서 값으로 스토어를 만들고 스토어 · 신청 이력을 남긴 뒤 storeId · 승인 시각을 돌려준다")
    void approve_whenPendingOrSupplement_createsStoreAndHistories(ApplicationStatus from) {
        // given
        StoreApplication application = application(27L, from);
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application));
        given(storeRepository.saveAndFlush(any(Store.class))).willAnswer(inv -> {
            Store store = inv.getArgument(0);
            ReflectionTestUtils.setField(store, "id", 3L);
            return store;
        });

        // when
        ApplicationApproveResponse response = service.approve(27L);

        // then
        assertThat(response.applicationId()).isEqualTo(27L);
        assertThat(response.status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(response.storeId()).isEqualTo(3L);
        assertThat(response.decidedAt()).isEqualTo(OffsetDateTime.parse("2026-10-28T14:30:00+09:00"));
        assertThat(application.getStatusChangedAt()).isEqualTo(NOW);

        ArgumentCaptor<Store> store = ArgumentCaptor.forClass(Store.class);
        verify(storeRepository).saveAndFlush(store.capture());
        assertThat(store.getValue().getStoreApplicationId()).isEqualTo(27L);
        assertThat(store.getValue().getUserId()).isEqualTo(4L);
        assertThat(store.getValue().getStoreName()).isEqualTo("곳간상회");
        assertThat(store.getValue().getSlug()).isEqualTo("gotgan");
        assertThat(store.getValue().getIntro()).isEqualTo("<p>소개</p>");
        assertThat(store.getValue().getCsPhone()).isEqualTo("010-0000-1234");

        ArgumentCaptor<StoreStatusHistory> storeHistory = ArgumentCaptor.forClass(StoreStatusHistory.class);
        verify(storeStatusHistoryRepository).save(storeHistory.capture());
        assertThat(storeHistory.getValue().getStoreId()).isEqualTo(3L);
        assertThat(storeHistory.getValue().getFromStatus()).isNull();
        assertThat(storeHistory.getValue().getToStatus()).isEqualTo(StoreStatus.ACTIVE);
        assertThat(storeHistory.getValue().getActorType()).isEqualTo(StoreActorType.ADMIN);

        ArgumentCaptor<ApplicationHistory> applicationHistory = ArgumentCaptor.forClass(ApplicationHistory.class);
        verify(applicationHistoryRepository).save(applicationHistory.capture());
        assertThat(applicationHistory.getValue().getFromStatus()).isEqualTo(from);
        assertThat(applicationHistory.getValue().getToStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(applicationHistory.getValue().getActorType()).isEqualTo(ActorType.ADMIN);

        verify(eventOutboxService).enqueue("StoreApproved", "3", ApplicationReviewService.storeApprovedEventId(27L), NOW,
                new StoreApprovedPayload(3L, 4L, "곳간상회", "seller@example.com",
                        new StoreApprovedPayload.SettlementAccount("국민", "000000-00-000000", "홍길동")));
    }

    @Test
    @DisplayName("없는 신청은 404 APPLY_NOT_FOUND")
    void approve_whenApplicationMissing_throwsNotFound() {
        // given
        given(storeApplicationRepository.findById(99L)).willReturn(Optional.empty());

        // when · then
        assertErrorCode(() -> service.approve(99L), ApplyErrorCode.APPLY_NOT_FOUND);
    }

    @Test
    @DisplayName("이미 반려된 신청은 409 APPLY_INVALID_STATUS 이고 스토어를 만들지 않는다")
    void approve_whenAlreadyRejected_throwsInvalidStatus() {
        // given
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application(27L, ApplicationStatus.REJECTED)));

        // when · then
        assertErrorCode(() -> service.approve(27L), ApplyErrorCode.APPLY_INVALID_STATUS);
        verify(storeRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("신청자에게 이미 스토어가 있으면 409 APPLY_STORE_ALREADY_EXISTS")
    void approve_whenApplicantHasStore_throwsStoreAlreadyExists() {
        // given
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application(27L, ApplicationStatus.PENDING)));
        given(storeRepository.existsByUserId(4L)).willReturn(true);

        // when · then
        assertErrorCode(() -> service.approve(27L), ApplyErrorCode.APPLY_STORE_ALREADY_EXISTS);
        verify(storeRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("스토어 주소가 그사이 다른 스토어와 겹치면 409 APPLY_SLUG_DUPLICATED")
    void approve_whenSlugTaken_throwsSlugDuplicated() {
        // given
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application(27L, ApplicationStatus.PENDING)));
        given(storeRepository.existsBySlug("gotgan")).willReturn(true);

        // when · then
        assertErrorCode(() -> service.approve(27L), ApplyErrorCode.APPLY_SLUG_DUPLICATED);
    }

    @Test
    @DisplayName("스토어명이 공백 · 대소문자만 다른 스토어와 겹치면 409 APPLY_STORE_NAME_DUPLICATED")
    void approve_whenStoreNameTaken_throwsStoreNameDuplicated() {
        // given
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application(27L, ApplicationStatus.PENDING)));
        given(storeRepository.existsByNormalizedStoreName("곳간상회")).willReturn(true);

        // when · then
        assertErrorCode(() -> service.approve(27L), ApplyErrorCode.APPLY_STORE_NAME_DUPLICATED);
    }

    @Test
    @DisplayName("다른 관리자가 먼저 처리해 버전이 달라지면 409 APPLY_INVALID_STATUS 이고 스토어를 만들지 않는다")
    void approve_whenVersionConflict_throwsInvalidStatus() {
        // given
        StoreApplication application = application(27L, ApplicationStatus.PENDING);
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application));
        willThrow(new ObjectOptimisticLockingFailureException(StoreApplication.class, 27L))
                .given(storeApplicationRepository).saveAndFlush(application);

        // when · then
        assertErrorCode(() -> service.approve(27L), ApplyErrorCode.APPLY_INVALID_STATUS);
        verify(storeRepository, never()).saveAndFlush(any());
        verify(eventOutboxService, never()).enqueue(anyString(), anyString(), anyString(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({
            "store.uk_store_slug, APPLY_SLUG_DUPLICATED",
            "store.uk_store_store_name, APPLY_STORE_NAME_DUPLICATED",
            "store.uk_store_user_id, APPLY_STORE_ALREADY_EXISTS",
            "store.uk_store_store_application_id, APPLY_INVALID_STATUS"
    })
    @DisplayName("검사 뒤 저장 순간 UNIQUE 제약에 걸리면 제약 이름으로 409 코드를 고르고 이력은 남기지 않는다")
    void approve_whenUniqueViolatedOnInsert_mapsConstraintToCode(String constraint, ApplyErrorCode expected) {
        // given
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application(27L, ApplicationStatus.PENDING)));
        given(storeRepository.saveAndFlush(any(Store.class))).willThrow(duplicate(constraint));

        // when · then
        assertErrorCode(() -> service.approve(27L), expected);
        verify(storeStatusHistoryRepository, never()).save(any());
        verify(applicationHistoryRepository, never()).save(any());
        verify(eventOutboxService, never()).enqueue(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("예상 밖 제약 위반은 409 로 바꾸지 않고 그대로 던진다")
    void duplicateCode_whenUnknownConstraint_rethrows() {
        // given
        DataIntegrityViolationException e = duplicate("store.fk_store_store_application");

        // when · then
        assertThatThrownBy(() -> ApplicationReviewService.duplicateCode(e)).isSameAs(e);
    }

    @Test
    @DisplayName("StoreApproved eventId 는 신청 ID 로 정해지는 UUID — 같은 신청은 항상 같은 값, 다른 신청은 다른 값")
    void storeApprovedEventId_whenSameApplication_isStable() {
        // when · then
        assertThat(ApplicationReviewService.storeApprovedEventId(27L))
                .isEqualTo(ApplicationReviewService.storeApprovedEventId(27L))
                .isNotEqualTo(ApplicationReviewService.storeApprovedEventId(28L));
        assertThat(UUID.fromString(ApplicationReviewService.storeApprovedEventId(27L)).version()).isEqualTo(3);
    }

    private static DataIntegrityViolationException duplicate(String key) {
        return new DataIntegrityViolationException("could not execute statement",
                new SQLIntegrityConstraintViolationException("Duplicate entry 'x' for key '" + key + "'"));
    }

    private static void assertErrorCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ApplyErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(code);
    }

    private static StoreApplication application(Long id, ApplicationStatus status) {
        StoreApplication application = StoreApplication.builder()
                .userId(4L).categoryId(1L).bizType(BizType.INDIVIDUAL)
                .companyName("곳간상회").ownerName("홍길동").bizRegNo("000-00-00001").ecommerceRegNo("2026-서울성동-0000")
                .managerPhone("010-0000-1234").managerEmail("seller@example.com").businessAddress("서울시 성동구")
                .storeName("곳간상회").slug("gotgan").intro("<p>소개</p>")
                .representativeProducts(List.of(
                        new RepresentativeProduct("리넨 셔츠", "셔츠", 39000),
                        new RepresentativeProduct("리넨 팬츠", "팬츠", 45000)))
                .bankName("국민").accountNo("000000-00-000000").accountHolder("홍길동")
                .marketingAgreed(false).submittedAt(LocalDateTime.of(2026, 10, 27, 10, 0))
                .build();
        ReflectionTestUtils.setField(application, "id", id);
        ReflectionTestUtils.setField(application, "status", status);
        return application;
    }
}
