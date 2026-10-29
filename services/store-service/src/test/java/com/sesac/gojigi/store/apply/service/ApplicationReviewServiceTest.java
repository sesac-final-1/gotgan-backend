package com.sesac.gojigi.store.apply.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.store.apply.dto.event.StoreApplicationRejectedPayload;
import com.sesac.gojigi.store.apply.dto.event.StoreApplicationSupplementRequestedPayload;
import com.sesac.gojigi.store.apply.dto.event.StoreApprovedPayload;
import com.sesac.gojigi.store.apply.dto.response.ApplicationApproveResponse;
import com.sesac.gojigi.store.apply.dto.response.ApplicationRejectResponse;
import com.sesac.gojigi.store.apply.dto.response.ApplicationSupplementResponse;
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
import com.sesac.gojigi.store.store.entity.Store;
import com.sesac.gojigi.store.store.entity.StoreActorType;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.entity.StoreStatusHistory;
import com.sesac.gojigi.store.store.repository.StoreRepository;
import com.sesac.gojigi.store.store.repository.StoreStatusHistoryRepository;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.Duration;
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
                storeRepository, storeStatusHistoryRepository, eventOutboxService, clock, new SupplementDeadline(Duration.ofDays(7)));
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

    @ParameterizedTest
    @CsvSource({"PENDING", "SUPPLEMENT"})
    @DisplayName("반려하면 신청을 REJECTED · 사유 저장 · 이력(사유 포함)을 남기고 StoreApplicationRejected 를 아웃박스에 남긴다")
    void reject_whenPendingOrSupplement_rejectsAndEnqueuesEvent(ApplicationStatus from) {
        // given
        StoreApplication application = application(27L, from);
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application));

        // when
        ApplicationRejectResponse response = service.reject(27L, "사업자등록증 정보가 신청서와 다릅니다");

        // then
        assertThat(response).isEqualTo(new ApplicationRejectResponse(27L, ApplicationStatus.REJECTED,
                OffsetDateTime.parse("2026-10-28T14:30:00+09:00")));
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(application.getStatusReason()).isEqualTo("사업자등록증 정보가 신청서와 다릅니다");
        assertThat(application.getStatusChangedAt()).isEqualTo(NOW); // 30일 재신청 제한 기준
        verify(storeApplicationRepository).saveAndFlush(application);

        ArgumentCaptor<ApplicationHistory> history = ArgumentCaptor.forClass(ApplicationHistory.class);
        verify(applicationHistoryRepository).save(history.capture());
        assertThat(history.getValue().getFromStatus()).isEqualTo(from);
        assertThat(history.getValue().getToStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(history.getValue().getActorType()).isEqualTo(ActorType.ADMIN);
        assertThat(history.getValue().getReason()).isEqualTo("사업자등록증 정보가 신청서와 다릅니다");

        verify(eventOutboxService).enqueue("StoreApplicationRejected", "27",
                ApplicationReviewService.applicationEventId("StoreApplicationRejected", 27L), NOW,
                new StoreApplicationRejectedPayload(27L, 4L, "곳간상회", "seller@example.com",
                        "사업자등록증 정보가 신청서와 다릅니다", OffsetDateTime.parse("2026-10-28T14:30:00+09:00")));
        verify(storeRepository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @CsvSource({"APPROVED", "REJECTED"})
    @DisplayName("이미 승인 · 반려된 신청을 반려하면 409 APPLY_INVALID_STATUS 이고 이력 · 이벤트를 남기지 않는다")
    void reject_whenAlreadyDecided_throwsInvalidStatus(ApplicationStatus from) {
        // given
        StoreApplication application = application(27L, from);
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application));

        // when · then
        assertErrorCode(() -> service.reject(27L, "사유"), ApplyErrorCode.APPLY_INVALID_STATUS);
        assertThat(application.getStatus()).isEqualTo(from);
        verify(applicationHistoryRepository, never()).save(any());
        verify(eventOutboxService, never()).enqueue(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("반려 저장 순간 다른 관리자가 먼저 처리해 버전이 달라지면 409 APPLY_INVALID_STATUS 이고 이력 · 이벤트를 남기지 않는다")
    void reject_whenVersionConflict_throwsInvalidStatus() {
        // given
        StoreApplication application = application(27L, ApplicationStatus.PENDING);
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application));
        willThrow(new ObjectOptimisticLockingFailureException(StoreApplication.class, 27L))
                .given(storeApplicationRepository).saveAndFlush(application);

        // when · then
        assertErrorCode(() -> service.reject(27L, "사유"), ApplyErrorCode.APPLY_INVALID_STATUS);
        verify(applicationHistoryRepository, never()).save(any());
        verify(eventOutboxService, never()).enqueue(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("없는 신청 반려는 404 APPLY_NOT_FOUND")
    void reject_whenApplicationMissing_throwsNotFound() {
        // given
        given(storeApplicationRepository.findById(99L)).willReturn(Optional.empty());

        // when · then
        assertErrorCode(() -> service.reject(99L, "사유"), ApplyErrorCode.APPLY_NOT_FOUND);
    }

    @Test
    @DisplayName("보완요청하면 신청을 SUPPLEMENT · 메시지 저장 · 이력을 남기고, 기한 +7일 · 서류 한글 이름으로 이벤트를 아웃박스에 남긴다")
    void requestSupplement_whenPending_supplementsAndEnqueuesEvent() {
        // given
        StoreApplication application = application(27L, ApplicationStatus.PENDING);
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application));

        // when
        ApplicationSupplementResponse response = service.requestSupplement(27L, "사본이 흐려 확인이 어렵습니다",
                List.of(DocType.ECOMMERCE_REG, DocType.PRODUCT_PHOTO));

        // then
        assertThat(response).isEqualTo(new ApplicationSupplementResponse(27L, ApplicationStatus.SUPPLEMENT,
                OffsetDateTime.parse("2026-11-04T14:30:00+09:00")));
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.SUPPLEMENT);
        assertThat(application.getStatusReason()).isEqualTo("사본이 흐려 확인이 어렵습니다");
        assertThat(application.getStatusChangedAt()).isEqualTo(NOW);
        verify(storeApplicationRepository).saveAndFlush(application);

        ArgumentCaptor<ApplicationHistory> history = ArgumentCaptor.forClass(ApplicationHistory.class);
        verify(applicationHistoryRepository).save(history.capture());
        assertThat(history.getValue().getFromStatus()).isEqualTo(ApplicationStatus.PENDING);
        assertThat(history.getValue().getToStatus()).isEqualTo(ApplicationStatus.SUPPLEMENT);
        assertThat(history.getValue().getActorType()).isEqualTo(ActorType.ADMIN);
        assertThat(history.getValue().getReason()).isEqualTo("사본이 흐려 확인이 어렵습니다");

        ArgumentCaptor<String> eventId = ArgumentCaptor.forClass(String.class);
        verify(eventOutboxService).enqueue(eq("StoreApplicationSupplementRequested"), eq("27"), eventId.capture(), eq(NOW),
                eq(new StoreApplicationSupplementRequestedPayload(27L, 4L, "곳간상회", "seller@example.com",
                        List.of("통신판매업 신고증", "상품 사진"), "사본이 흐려 확인이 어렵습니다",
                        OffsetDateTime.parse("2026-11-04T14:30:00+09:00"))));
        assertThat(UUID.fromString(eventId.getValue())).isNotNull();
    }

    @Test
    @DisplayName("서류가 아닌 내용 보완은 requestedDocs 빈 목록으로 — 이벤트도 빈 목록")
    void requestSupplement_whenNoDocs_enqueuesEmptyList() {
        // given
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application(27L, ApplicationStatus.PENDING)));

        // when
        service.requestSupplement(27L, "판매 예정 상품 설명을 보완해 주세요", List.of());

        // then
        ArgumentCaptor<StoreApplicationSupplementRequestedPayload> payload =
                ArgumentCaptor.forClass(StoreApplicationSupplementRequestedPayload.class);
        verify(eventOutboxService).enqueue(anyString(), anyString(), anyString(), any(), payload.capture());
        assertThat(payload.getValue().requestedDocs()).isEmpty();
    }

    @Test
    @DisplayName("보완요청 eventId 는 매번 새 값 — 한 신청에 보완요청이 여러 번 생길 수 있어서")
    void requestSupplement_twice_usesDifferentEventIds() {
        // given — 보완 → (재제출로) 다시 심사대기 → 다시 보완
        StoreApplication application = application(27L, ApplicationStatus.PENDING);
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application));
        service.requestSupplement(27L, "첫 보완", List.of());
        ReflectionTestUtils.setField(application, "status", ApplicationStatus.PENDING);

        // when
        service.requestSupplement(27L, "두 번째 보완", List.of());

        // then
        ArgumentCaptor<String> eventId = ArgumentCaptor.forClass(String.class);
        verify(eventOutboxService, times(2)).enqueue(anyString(), anyString(), eventId.capture(), any(), any());
        assertThat(eventId.getAllValues().get(0)).isNotEqualTo(eventId.getAllValues().get(1));
    }

    @ParameterizedTest
    @CsvSource({"SUPPLEMENT", "APPROVED", "REJECTED"})
    @DisplayName("심사대기가 아닌 신청(보완요청 중 · 승인 · 반려)에 보완요청하면 409 APPLY_INVALID_STATUS 이고 이력 · 이벤트를 남기지 않는다")
    void requestSupplement_whenNotPending_throwsInvalidStatus(ApplicationStatus from) {
        // given
        StoreApplication application = application(27L, from);
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application));

        // when · then
        assertErrorCode(() -> service.requestSupplement(27L, "보완", List.of()), ApplyErrorCode.APPLY_INVALID_STATUS);
        assertThat(application.getStatus()).isEqualTo(from);
        verify(applicationHistoryRepository, never()).save(any());
        verify(eventOutboxService, never()).enqueue(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("보완 서류가 중복되면 400 INVALID_INPUT — 신청을 조회하지 않는다")
    void requestSupplement_whenDocsDuplicated_throwsInvalidInput() {
        // when · then
        assertThatThrownBy(() -> service.requestSupplement(27L, "보완", List.of(DocType.BANK_COPY, DocType.BANK_COPY)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_INPUT);
        verify(storeApplicationRepository, never()).findById(any());
    }

    @Test
    @DisplayName("보완요청 저장 순간 버전이 달라지면 409 APPLY_INVALID_STATUS / 없는 신청은 404 APPLY_NOT_FOUND")
    void requestSupplement_whenConflictOrMissing_throws() {
        // given
        StoreApplication application = application(27L, ApplicationStatus.PENDING);
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application));
        given(storeApplicationRepository.findById(99L)).willReturn(Optional.empty());
        willThrow(new ObjectOptimisticLockingFailureException(StoreApplication.class, 27L))
                .given(storeApplicationRepository).saveAndFlush(application);

        // when · then
        assertErrorCode(() -> service.requestSupplement(27L, "보완", List.of()), ApplyErrorCode.APPLY_INVALID_STATUS);
        assertErrorCode(() -> service.requestSupplement(99L, "보완", List.of()), ApplyErrorCode.APPLY_NOT_FOUND);
        verify(eventOutboxService, never()).enqueue(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("반려 eventId 는 신청 ID 로 정해지고 같은 신청의 승인 eventId 와 다르다")
    void applicationEventId_whenRejected_differsFromApproved() {
        // when · then
        assertThat(ApplicationReviewService.applicationEventId("StoreApplicationRejected", 27L))
                .isEqualTo(ApplicationReviewService.applicationEventId("StoreApplicationRejected", 27L))
                .isNotEqualTo(ApplicationReviewService.storeApprovedEventId(27L));
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

    // ── 보완 기한 지난 신청 자동 반려 (7-1절) ──

    @Test
    @DisplayName("자동 반려 — 기한 정각(보완요청 + 7일)이면 반려 · 이력 SYSTEM · 관리자 반려와 같은 이벤트(신청 ID 고정 eventId · 고정 사유)")
    void rejectExpiredSupplement_whenDue_rejectsAsSystem() {
        // given
        StoreApplication application = supplementedAt(NOW.minusDays(7));
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application));

        // when
        boolean rejected = service.rejectExpiredSupplement(27L);

        // then
        assertThat(rejected).isTrue();
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(application.getStatusReason()).isEqualTo(ApplicationReviewService.AUTO_REJECT_REASON);
        assertThat(application.getRequestedDocs()).isNull();
        assertThat(application.getStatusChangedAt()).isEqualTo(NOW);   // 30일 재신청 제한 기준
        verify(storeApplicationRepository).saveAndFlush(application);

        ArgumentCaptor<ApplicationHistory> history = ArgumentCaptor.forClass(ApplicationHistory.class);
        verify(applicationHistoryRepository).save(history.capture());
        assertThat(history.getValue().getFromStatus()).isEqualTo(ApplicationStatus.SUPPLEMENT);
        assertThat(history.getValue().getToStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(history.getValue().getActorType()).isEqualTo(ActorType.SYSTEM);

        verify(eventOutboxService).enqueue("StoreApplicationRejected", "27",
                ApplicationReviewService.applicationEventId("StoreApplicationRejected", 27L), NOW,
                new StoreApplicationRejectedPayload(27L, 4L, "곳간상회", "seller@example.com",
                        "보완 기한 안에 다시 제출하지 않아 자동 반려되었습니다.", OffsetDateTime.parse("2026-10-28T14:30:00+09:00")));
    }

    @Test
    @DisplayName("자동 반려 — 기한 1초 전 · 이미 재제출(PENDING) · 없는 신청은 건너뛴다(false), 저장 · 이벤트 없음")
    void rejectExpiredSupplement_whenNotTarget_skips() {
        // given
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(supplementedAt(NOW.minusDays(7).plusSeconds(1))));
        given(storeApplicationRepository.findById(28L)).willReturn(Optional.of(application(28L, ApplicationStatus.PENDING)));
        given(storeApplicationRepository.findById(29L)).willReturn(Optional.empty());

        // when · then
        assertThat(service.rejectExpiredSupplement(27L)).isFalse();
        assertThat(service.rejectExpiredSupplement(28L)).isFalse();
        assertThat(service.rejectExpiredSupplement(29L)).isFalse();
        verify(storeApplicationRepository, never()).saveAndFlush(any());
        verify(eventOutboxService, never()).enqueue(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("자동 반려 저장 순간 재제출이 먼저 저장됐으면(@Version) 409 — 이력 · 이벤트 없음(스케줄러가 건너뜀)")
    void rejectExpiredSupplement_whenVersionConflict_throwsInvalidStatus() {
        // given
        StoreApplication application = supplementedAt(NOW.minusDays(8));
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application));
        willThrow(new ObjectOptimisticLockingFailureException(StoreApplication.class, 27L))
                .given(storeApplicationRepository).saveAndFlush(application);

        // when · then
        assertErrorCode(() -> service.rejectExpiredSupplement(27L), ApplyErrorCode.APPLY_INVALID_STATUS);
        verify(applicationHistoryRepository, never()).save(any());
        verify(eventOutboxService, never()).enqueue(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("보완 기한이 설정값이면(시연 3분) 그 값으로 판단한다")
    void rejectExpiredSupplement_withShortDeadline_usesSetting() {
        // given
        ApplicationReviewService demo = new ApplicationReviewService(storeApplicationRepository, applicationHistoryRepository,
                storeRepository, storeStatusHistoryRepository, eventOutboxService,
                Clock.fixed(NOW.atZone(KST).toInstant(), KST), new SupplementDeadline(Duration.ofMinutes(3)));
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(supplementedAt(NOW.minusMinutes(3))));

        // when · then
        assertThat(demo.rejectExpiredSupplement(27L)).isTrue();
    }

    /** 보완요청 중 신청 — 보완요청 시각만 다르게 */
    private static StoreApplication supplementedAt(LocalDateTime requestedAt) {
        StoreApplication application = application(27L, ApplicationStatus.PENDING);
        application.requestSupplement("사본이 흐립니다", List.of(DocType.BANK_COPY), requestedAt);
        return application;
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
