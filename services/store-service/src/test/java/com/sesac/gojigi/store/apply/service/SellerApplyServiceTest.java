package com.sesac.gojigi.store.apply.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.store.apply.client.SellerDocumentClient.FileMeta;
import com.sesac.gojigi.store.apply.client.SellerDocumentClient;
import com.sesac.gojigi.store.apply.dto.request.ApplicationResubmitRequest;
import com.sesac.gojigi.store.apply.dto.request.SellerApplyRequest.Agreements;
import com.sesac.gojigi.store.apply.dto.request.SellerApplyRequest.DocumentItem;
import com.sesac.gojigi.store.apply.dto.request.SellerApplyRequest.ProductItem;
import com.sesac.gojigi.store.apply.dto.request.SellerApplyRequest.SettlementAccount;
import com.sesac.gojigi.store.apply.dto.request.SellerApplyRequest;
import com.sesac.gojigi.store.apply.dto.response.ApplicationResubmitResponse;
import com.sesac.gojigi.store.apply.dto.response.SellerApplyResponse;
import com.sesac.gojigi.store.apply.entity.ActorType;
import com.sesac.gojigi.store.apply.entity.ApplicationDocument;
import com.sesac.gojigi.store.apply.entity.ApplicationHistory;
import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.entity.BizType;
import com.sesac.gojigi.store.apply.entity.DocType;
import com.sesac.gojigi.store.apply.entity.ExpectedMonthlySales;
import com.sesac.gojigi.store.apply.entity.StoreApplication;
import com.sesac.gojigi.store.apply.exception.ApplyErrorCode;
import com.sesac.gojigi.store.apply.repository.ApplicationDocumentRepository;
import com.sesac.gojigi.store.apply.repository.ApplicationHistoryRepository;
import com.sesac.gojigi.store.apply.repository.StoreApplicationRepository;
import com.sesac.gojigi.store.store.repository.StoreRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class SellerApplyServiceTest {

    private static final Long USER_ID = 4L;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 14, 5, 11);
    private static final FileMeta PDF = new FileMeta("application/pdf", 300_000);
    private static final FileMeta JPG = new FileMeta("image/jpeg", 2_000_000);

    @Mock
    private StoreRepository storeRepository;
    @Mock
    private StoreApplicationRepository storeApplicationRepository;
    @Mock
    private ApplicationDocumentRepository applicationDocumentRepository;
    @Mock
    private ApplicationHistoryRepository applicationHistoryRepository;
    @Mock
    private SellerDocumentClient sellerDocumentClient;

    private SellerApplyService sellerApplyService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(ZonedDateTime.of(NOW, KST).toInstant(), KST);
        TransactionTemplate transactionTemplate = new TransactionTemplate(mock(PlatformTransactionManager.class));
        sellerApplyService = new SellerApplyService(storeRepository, storeApplicationRepository,
                applicationDocumentRepository, applicationHistoryRepository, sellerDocumentClient, transactionTemplate, clock,
                new SupplementDeadline(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("정상 신청 — 심사대기 신청서 · 서류 6건 · 최초 이력을 저장하고 접수번호를 돌려준다")
    void apply_validRequest_savesApplicationDocumentsAndHistory() {
        // given
        givenFilesUploaded();
        given(storeApplicationRepository.save(any(StoreApplication.class))).willAnswer(invocation -> {
            StoreApplication application = invocation.getArgument(0);
            ReflectionTestUtils.setField(application, "id", 27L);
            return application;
        });

        // when
        SellerApplyResponse response = sellerApplyService.apply(USER_ID, request(documents()));

        // then
        assertThat(response.applicationId()).isEqualTo(27L);
        assertThat(response.applicationNo()).isEqualTo("AP-2610-027");
        assertThat(response.status()).isEqualTo(ApplicationStatus.PENDING);
        assertThat(response.submittedAt()).hasToString("2026-10-22T14:05:11+09:00");

        ArgumentCaptor<StoreApplication> application = ArgumentCaptor.forClass(StoreApplication.class);
        verify(storeApplicationRepository).save(application.capture());
        assertThat(application.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(application.getValue().getTermsAgreedAt()).isEqualTo(NOW);
        assertThat(application.getValue().getStatusChangedAt()).isEqualTo(NOW);
        assertThat(application.getValue().isMarketingAgreed()).isFalse();
        assertThat(application.getValue().getIntro()).isEqualTo("<p>리넨 의류를 만듭니다.</p>");
        assertThat(application.getValue().getRepresentativeProducts()).hasSize(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ApplicationDocument>> saved = ArgumentCaptor.forClass(List.class);
        verify(applicationDocumentRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(6)
                .allSatisfy(d -> assertThat(d.getStoreApplicationId()).isEqualTo(27L))
                .allSatisfy(d -> assertThat(d.isCurrent()).isTrue());
        assertThat(saved.getValue()).filteredOn(d -> d.getDocType() == DocType.BUSINESS_REG)
                .singleElement().satisfies(d -> {
                    assertThat(d.getContentType()).isEqualTo("application/pdf");   // 클라이언트 값이 아니라 S3 값
                    assertThat(d.getFileSize()).isEqualTo(300_000);
                });

        ArgumentCaptor<ApplicationHistory> history = ArgumentCaptor.forClass(ApplicationHistory.class);
        verify(applicationHistoryRepository).save(history.capture());
        assertThat(history.getValue().getFromStatus()).isNull();
        assertThat(history.getValue().getToStatus()).isEqualTo(ApplicationStatus.PENDING);
        assertThat(history.getValue().getActorType()).isEqualTo(ActorType.APPLICANT);
    }

    @Test
    @DisplayName("소개 HTML 은 정화해서 저장 — script 는 지운다")
    void apply_introWithScript_sanitized() {
        // given
        givenFilesUploaded();
        given(storeApplicationRepository.save(any(StoreApplication.class))).willAnswer(invocation -> invocation.getArgument(0));
        SellerApplyRequest request = request(documents(), "<p>안녕하세요<script>alert(1)</script></p>");

        // when
        sellerApplyService.apply(USER_ID, request);

        // then
        ArgumentCaptor<StoreApplication> application = ArgumentCaptor.forClass(StoreApplication.class);
        verify(storeApplicationRepository).save(application.capture());
        assertThat(application.getValue().getIntro()).isEqualTo("<p>안녕하세요</p>");
    }

    @Test
    @DisplayName("이미 스토어가 있는 회원(폐점 포함) — 409 APPLY_STORE_ALREADY_EXISTS, 저장하지 않는다")
    void apply_userHasStore_throwsStoreAlreadyExists() {
        // given
        given(storeRepository.existsByUserId(USER_ID)).willReturn(true);

        // when · then
        assertThatThrownBy(() -> sellerApplyService.apply(USER_ID, request(documents())))
                .extracting("errorCode").isEqualTo(ApplyErrorCode.APPLY_STORE_ALREADY_EXISTS);
        verify(storeApplicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("진행 중 신청이 있으면 409 APPLY_IN_PROGRESS")
    void apply_applicationInProgress_throwsInProgress() {
        // given
        given(storeApplicationRepository.existsByUserIdAndStatusIn(USER_ID, SellerApplyService.IN_PROGRESS)).willReturn(true);

        // when · then
        assertThatThrownBy(() -> sellerApplyService.apply(USER_ID, request(documents())))
                .extracting("errorCode").isEqualTo(ApplyErrorCode.APPLY_IN_PROGRESS);
    }

    @Test
    @DisplayName("반려 후 30일 안이면 409 APPLY_REAPPLY_RESTRICTED, 메시지에 신청 가능일")
    void apply_rejectedWithin30Days_throwsReapplyRestricted() {
        // given — 10/1 반려 → 10/31 부터 가능
        givenRejectedAt(LocalDateTime.of(2026, 10, 1, 9, 0));

        // when · then
        assertThatThrownBy(() -> sellerApplyService.apply(USER_ID, request(documents())))
                .hasMessageContaining("2026-10-31")
                .extracting("errorCode").isEqualTo(ApplyErrorCode.APPLY_REAPPLY_RESTRICTED);
    }

    @Test
    @DisplayName("반려 후 30일이 지났으면 다시 신청할 수 있다")
    void apply_rejectedOver30DaysAgo_accepted() {
        // given — 9/22 14:05 반려 → 정확히 30일 뒤인 지금부터 가능
        givenRejectedAt(NOW.minusDays(30));
        givenFilesUploaded();
        given(storeApplicationRepository.save(any(StoreApplication.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        sellerApplyService.apply(USER_ID, request(documents()));

        // then
        verify(storeApplicationRepository).save(any(StoreApplication.class));
    }

    @Test
    @DisplayName("slug 가 스토어 또는 진행 중 신청과 겹치면 409 APPLY_SLUG_DUPLICATED")
    void apply_slugTaken_throwsSlugDuplicated() {
        // given
        given(storeApplicationRepository.existsBySlugAndStatusIn("linen-lab", SellerApplyService.IN_PROGRESS)).willReturn(true);

        // when · then
        assertThatThrownBy(() -> sellerApplyService.apply(USER_ID, request(documents())))
                .extracting("errorCode").isEqualTo(ApplyErrorCode.APPLY_SLUG_DUPLICATED);
    }

    @Test
    @DisplayName("스토어명은 공백 · 대소문자를 빼고 비교 — 겹치면 409 APPLY_STORE_NAME_DUPLICATED")
    void apply_storeNameTaken_throwsStoreNameDuplicated() {
        // given — 요청 스토어명 "Linen Lab"
        given(storeRepository.existsByNormalizedStoreName("linenlab")).willReturn(true);

        // when · then
        assertThatThrownBy(() -> sellerApplyService.apply(USER_ID, request(documents())))
                .extracting("errorCode").isEqualTo(ApplyErrorCode.APPLY_STORE_NAME_DUPLICATED);
    }

    @Test
    @DisplayName("상품 사진이 2장이면 400 APPLY_DOCUMENT_INVALID — S3 를 부르지 않는다")
    void apply_tooFewPhotos_throwsDocumentInvalid() {
        // given
        List<DocumentItem> documents = new ArrayList<>(documents());
        documents.removeLast();

        // when · then
        assertDocumentInvalid(documents, "3~10장");
        verify(sellerDocumentClient, never()).head(anyString());
    }

    @Test
    @DisplayName("상품 사진이 11장이면 400 APPLY_DOCUMENT_INVALID")
    void apply_tooManyPhotos_throwsDocumentInvalid() {
        // given
        List<DocumentItem> documents = new ArrayList<>(documents());
        for (int i = 4; i <= 11; i++) {
            documents.add(doc(DocType.PRODUCT_PHOTO, "photo" + i + ".jpg"));
        }

        // when · then
        assertDocumentInvalid(documents, "3~10장");
    }

    @Test
    @DisplayName("사업자등록증이 없거나 두 개면 400 APPLY_DOCUMENT_INVALID")
    void apply_businessRegMissing_throwsDocumentInvalid() {
        // given
        List<DocumentItem> documents = new ArrayList<>(documents());
        documents.removeFirst();

        // when · then
        assertDocumentInvalid(documents, "사업자등록증");
    }

    @Test
    @DisplayName("남의 키(다른 userId prefix)는 400 APPLY_DOCUMENT_INVALID")
    void apply_otherUsersKey_throwsDocumentInvalid() {
        // given
        List<DocumentItem> documents = new ArrayList<>(documents());
        documents.set(0, new DocumentItem(DocType.BUSINESS_REG, "applications/5/stolen.pdf", "사업자등록증.pdf"));

        // when · then
        assertDocumentInvalid(documents, "사업자등록증");
    }

    @Test
    @DisplayName("같은 키를 두 번 보내면 400 APPLY_DOCUMENT_INVALID")
    void apply_duplicateKey_throwsDocumentInvalid() {
        // given
        List<DocumentItem> documents = new ArrayList<>(documents());
        documents.set(5, documents.get(4));

        // when · then
        assertDocumentInvalid(documents, "두 번");
    }

    @Test
    @DisplayName("S3 에 없는 파일은 400 APPLY_DOCUMENT_INVALID")
    void apply_fileNotUploaded_throwsDocumentInvalid() {
        // given
        given(sellerDocumentClient.head(anyString())).willReturn(Optional.empty());

        // when · then
        assertDocumentInvalid(documents(), "업로드되지 않았습니다");
    }

    @Test
    @DisplayName("상품 사진이 PDF 면 400 APPLY_DOCUMENT_INVALID — 사진은 JPG · PNG 만")
    void apply_photoIsPdf_throwsDocumentInvalid() {
        // given
        given(sellerDocumentClient.head(anyString())).willReturn(Optional.of(PDF));

        // when · then
        assertDocumentInvalid(documents(), "JPG · PNG");
    }

    @Test
    @DisplayName("서류 형식이 허용 목록 밖이면 400 APPLY_DOCUMENT_INVALID")
    void apply_documentIsZip_throwsDocumentInvalid() {
        // given
        given(sellerDocumentClient.head(anyString())).willReturn(Optional.of(new FileMeta("application/zip", 1000)));

        // when · then
        assertDocumentInvalid(documents(), "PDF · JPG · PNG");
    }

    @Test
    @DisplayName("10MB 를 넘는 파일은 400 APPLY_DOCUMENT_INVALID")
    void apply_fileOver10Mb_throwsDocumentInvalid() {
        // given
        given(sellerDocumentClient.head(anyString())).willReturn(Optional.of(new FileMeta("application/pdf", 10L * 1024 * 1024 + 1)));

        // when · then
        assertDocumentInvalid(documents(), "10MB");
    }

    @Test
    @DisplayName("S3 장애는 502 EXTERNAL_API_ERROR 그대로 — 저장하지 않는다")
    void apply_s3Unavailable_propagatesExternalApiError() {
        // given
        given(sellerDocumentClient.head(anyString()))
                .willThrow(new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR));

        // when · then
        assertThatThrownBy(() -> sellerApplyService.apply(USER_ID, request(documents())))
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
        verify(storeApplicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("접수번호는 신청 ID 를 3자리로 채우고, 1000 이상이면 그대로 늘어난다")
    void applicationNo_idPadding_atLeastThreeDigits() {
        assertThat(SellerApplyResponse.applicationNo(7L, NOW)).isEqualTo("AP-2610-007");
        assertThat(SellerApplyResponse.applicationNo(1234L, NOW)).isEqualTo("AP-2610-1234");
    }

    // ── 보완 재제출 (2-3절) ──

    @Test
    @DisplayName("재제출 — 요청 서류를 새 행으로 · 같은 종류 이전 서류는 이전 제출본으로, 보낸 항목만 고치고 심사대기 + 이력에 바뀐 것")
    void resubmit_requestedDocsAndFields_savesPendingAndReplacesDocuments() {
        // given
        StoreApplication application = supplemented(List.of(DocType.ECOMMERCE_REG));
        ApplicationDocument old = ApplicationDocument.of(27L, DocType.ECOMMERCE_REG, "applications/4/old.pdf", "old.pdf", "application/pdf", 1);
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application));
        given(applicationDocumentRepository.findAllByStoreApplicationIdAndCurrentTrueAndDocTypeIn(27L, List.of(DocType.ECOMMERCE_REG)))
                .willReturn(List.of(old));
        givenFilesUploaded();
        ApplicationResubmitRequest request = resubmit(List.of(doc(DocType.ECOMMERCE_REG, "ecom2.pdf")), "  새상호  ",
                new SettlementAccount("신한", "111-111", "홍길동"));

        // when
        ApplicationResubmitResponse response = sellerApplyService.resubmit(USER_ID, 27L, request);

        // then
        assertThat(response.status()).isEqualTo(ApplicationStatus.PENDING);
        assertThat(response.resubmittedAt()).hasToString("2026-10-22T14:05:11+09:00");
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.PENDING);
        assertThat(application.getStatusReason()).isNull();
        assertThat(application.getRequestedDocs()).isNull();
        assertThat(application.getStatusChangedAt()).isEqualTo(NOW);
        assertThat(application.getCompanyName()).isEqualTo("새상호");
        assertThat(application.getBankName()).isEqualTo("신한");
        assertThat(application.getManagerEmail()).isEqualTo("seller@example.com");   // 보내지 않은 항목은 그대로
        assertThat(application.getBizRegNo()).isEqualTo("000-00-00099");
        assertThat(old.isCurrent()).isFalse();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ApplicationDocument>> saved = ArgumentCaptor.forClass(List.class);
        verify(applicationDocumentRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(d -> {
            assertThat(d.getDocType()).isEqualTo(DocType.ECOMMERCE_REG);
            assertThat(d.getS3Key()).isEqualTo("applications/4/ecom2.pdf");
            assertThat(d.isCurrent()).isTrue();
        });
        ArgumentCaptor<ApplicationHistory> history = ArgumentCaptor.forClass(ApplicationHistory.class);
        verify(applicationHistoryRepository).save(history.capture());
        assertThat(history.getValue().getFromStatus()).isEqualTo(ApplicationStatus.SUPPLEMENT);
        assertThat(history.getValue().getToStatus()).isEqualTo(ApplicationStatus.PENDING);
        assertThat(history.getValue().getActorType()).isEqualTo(ActorType.APPLICANT);
        assertThat(history.getValue().getReason()).isEqualTo("재제출 — 서류: 통신판매업 신고증 / 수정: 상호 · 정산 계좌");
    }

    @Test
    @DisplayName("내용 보완(요청 서류 []) — 서류 없이 항목만 고쳐 재제출, 서류 · S3 는 건드리지 않는다")
    void resubmit_contentOnly_savesWithoutDocuments() {
        // given
        StoreApplication application = supplemented(List.of());
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application));
        ApplicationResubmitRequest request = new ApplicationResubmitRequest(null, null, null, null, null, null, "2026-서울성동-1234", null, null, null, null,
                null, null, null, null);

        // when
        sellerApplyService.resubmit(USER_ID, 27L, request);

        // then
        assertThat(application.getEcommerceRegNo()).isEqualTo("2026-서울성동-1234");
        verify(sellerDocumentClient, never()).head(anyString());
        verify(applicationDocumentRepository, never()).saveAll(anyList());
        ArgumentCaptor<ApplicationHistory> history = ArgumentCaptor.forClass(ApplicationHistory.class);
        verify(applicationHistoryRepository).save(history.capture());
        assertThat(history.getValue().getReason()).isEqualTo("재제출 — 수정: 통신판매업 신고번호");
    }

    @Test
    @DisplayName("남의 신청 · 없는 신청은 404 APPLY_NOT_FOUND")
    void resubmit_otherUsersOrMissing_throwsNotFound() {
        // given
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(supplemented(List.of())));
        given(storeApplicationRepository.findById(99L)).willReturn(Optional.empty());

        // when · then
        assertThatThrownBy(() -> sellerApplyService.resubmit(5L, 27L, emptyResubmit()))
                .extracting("errorCode").isEqualTo(ApplyErrorCode.APPLY_NOT_FOUND);
        assertThatThrownBy(() -> sellerApplyService.resubmit(USER_ID, 99L, emptyResubmit()))
                .extracting("errorCode").isEqualTo(ApplyErrorCode.APPLY_NOT_FOUND);
    }

    @Test
    @DisplayName("보완요청 중이 아니거나 기한(+7일)이 지났으면 409 — S3 확인 전에 거절")
    void resubmit_notSupplementOrExpired_throwsInvalidStatus() {
        // given
        StoreApplication pending = application();
        StoreApplication expired = application();
        expired.requestSupplement("사본", List.of(), NOW.minusDays(7));   // 기한 = 지금 → 지남
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(pending));
        given(storeApplicationRepository.findById(28L)).willReturn(Optional.of(expired));

        // when · then
        assertThatThrownBy(() -> sellerApplyService.resubmit(USER_ID, 27L, emptyResubmit()))
                .hasMessageContaining("보완요청 중인 신청만")
                .extracting("errorCode").isEqualTo(ApplyErrorCode.APPLY_INVALID_STATUS);
        assertThatThrownBy(() -> sellerApplyService.resubmit(USER_ID, 28L, emptyResubmit()))
                .hasMessageContaining("보완 기한이 지나")
                .extracting("errorCode").isEqualTo(ApplyErrorCode.APPLY_INVALID_STATUS);
        verify(sellerDocumentClient, never()).head(anyString());
    }

    @Test
    @DisplayName("서류 구성 — 요청 서류 빠짐 · 요청 밖 종류 · 사진 2장 · 남의 키는 400 APPLY_DOCUMENT_INVALID")
    void resubmit_invalidDocuments_throwsDocumentInvalid() {
        // given
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(supplemented(List.of(DocType.PRODUCT_PHOTO))));

        // when · then
        assertResubmitInvalid(List.of(), "대표 상품 사진을 다시 올려 주세요");
        assertResubmitInvalid(List.of(doc(DocType.PRODUCT_PHOTO, "p1.jpg"), doc(DocType.PRODUCT_PHOTO, "p2.jpg"),
                doc(DocType.PRODUCT_PHOTO, "p3.jpg"), doc(DocType.BANK_COPY, "bank.pdf")), "보완 요청받지 않은 서류입니다: 통장 사본");
        assertResubmitInvalid(List.of(doc(DocType.PRODUCT_PHOTO, "p1.jpg"), doc(DocType.PRODUCT_PHOTO, "p2.jpg")),
                "대표 상품 사진은 3~10장");
        assertResubmitInvalid(List.of(doc(DocType.PRODUCT_PHOTO, "p1.jpg"), doc(DocType.PRODUCT_PHOTO, "p2.jpg"),
                new DocumentItem(DocType.PRODUCT_PHOTO, "applications/5/p3.jpg", "p3.jpg")), "파일이 올바르지 않습니다");
        verify(sellerDocumentClient, never()).head(anyString());
    }

    @Test
    @DisplayName("10/29 전 보완요청(요청 서류 기록 없음)은 낸 종류의 개수만 본다")
    void resubmit_legacySupplementWithoutRequestedDocs_checksSubmittedTypesOnly() {
        // given
        StoreApplication legacy = supplemented(List.of());
        ReflectionTestUtils.setField(legacy, "requestedDocs", null);
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(legacy));
        givenFilesUploaded();

        // when
        sellerApplyService.resubmit(USER_ID, 27L, resubmit(List.of(doc(DocType.BANK_COPY, "bank2.pdf")), null, null));

        // then
        assertThat(legacy.getStatus()).isEqualTo(ApplicationStatus.PENDING);
        verify(applicationDocumentRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("저장 순간 자동 반려 · 관리자 처리가 먼저 끝났으면(@Version) 409 — 서류 · 이력은 저장 안 함")
    void resubmit_concurrentDecision_throwsInvalidStatus() {
        // given
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(supplemented(List.of())));
        given(storeApplicationRepository.saveAndFlush(any(StoreApplication.class)))
                .willThrow(new ObjectOptimisticLockingFailureException(StoreApplication.class, 27L));

        // when · then
        assertThatThrownBy(() -> sellerApplyService.resubmit(USER_ID, 27L, emptyResubmit()))
                .extracting("errorCode").isEqualTo(ApplyErrorCode.APPLY_INVALID_STATUS);
        verify(applicationHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("사업자등록번호를 고치면 사업자등록증 필수 — 없으면 400, 있으면 보완요청에 없던 서류여도 받고 이력에 남김")
    void resubmit_bizRegNoChanged_requiresBusinessRegistration() {
        // given — 보완요청은 통신판매업 신고증만
        StoreApplication application = supplemented(List.of(DocType.ECOMMERCE_REG));
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application));
        List<DocumentItem> ecommerceOnly = List.of(doc(DocType.ECOMMERCE_REG, "ecom2.pdf"));

        // when · then — 사업자등록증 없이 번호만 고침
        assertThatThrownBy(() -> sellerApplyService.resubmit(USER_ID, 27L,
                resubmitBusiness(null, null, "000-00-00100", ecommerceOnly)))
                .hasMessageContaining("사업자등록증도 다시 올려 주세요")
                .extracting("errorCode").isEqualTo(ApplyErrorCode.APPLY_DOCUMENT_INVALID);
        verify(sellerDocumentClient, never()).head(anyString());

        // when — 사업자등록증까지
        givenFilesUploaded();
        sellerApplyService.resubmit(USER_ID, 27L, resubmitBusiness(BizType.CORPORATE, " 김대표 ", "000-00-00100",
                List.of(doc(DocType.ECOMMERCE_REG, "ecom2.pdf"), doc(DocType.BUSINESS_REG, "biz2.pdf"))));

        // then
        assertThat(application.getBizRegNo()).isEqualTo("000-00-00100");
        assertThat(application.getOwnerName()).isEqualTo("김대표");
        assertThat(application.getBizType()).isEqualTo(BizType.CORPORATE);
        ArgumentCaptor<ApplicationHistory> history = ArgumentCaptor.forClass(ApplicationHistory.class);
        verify(applicationHistoryRepository).save(history.capture());
        assertThat(history.getValue().getReason())
                .isEqualTo("재제출 — 서류: 통신판매업 신고증 · 사업자등록증 / 수정: 사업자 구분 · 대표자 · 사업자등록번호");
    }

    @Test
    @DisplayName("사업자 정보를 지금 값 그대로 보내면 바뀐 게 아니라 사업자등록증을 요구하지 않는다")
    void resubmit_sameBusinessValues_doesNotRequireBusinessRegistration() {
        // given
        StoreApplication application = supplemented(List.of());
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application));

        // when
        sellerApplyService.resubmit(USER_ID, 27L, resubmitBusiness(BizType.INDIVIDUAL, "홍길동", "000-00-00099", null));

        // then
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.PENDING);
        ArgumentCaptor<ApplicationHistory> history = ArgumentCaptor.forClass(ApplicationHistory.class);
        verify(applicationHistoryRepository).save(history.capture());
        assertThat(history.getValue().getReason()).isEqualTo("재제출");
    }

    @Test
    @DisplayName("스토어명 · 주소 변경 — 스토어 · 자기를 뺀 진행 중 신청과 겹치면 409, 안 겹치면 저장하고 이력에 남김")
    void resubmit_renameStore_checksDuplicatesExcludingSelf() {
        // given — 승인 때 이름이 겹쳐 보완요청된 신청(스펙 STR-04)
        StoreApplication application = supplemented(List.of());
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application));
        given(storeRepository.existsBySlug("taken")).willReturn(true);
        given(storeApplicationRepository.existsByNormalizedStoreNameAndStatusInAndIdNot("다른가게", SellerApplyService.IN_PROGRESS, 27L))
                .willReturn(true);

        // when · then
        assertThatThrownBy(() -> sellerApplyService.resubmit(USER_ID, 27L, resubmitStore(null, "taken")))
                .extracting("errorCode").isEqualTo(ApplyErrorCode.APPLY_SLUG_DUPLICATED);
        assertThatThrownBy(() -> sellerApplyService.resubmit(USER_ID, 27L, resubmitStore("다른 가게", null)))
                .extracting("errorCode").isEqualTo(ApplyErrorCode.APPLY_STORE_NAME_DUPLICATED);

        // when — 겹치지 않는 새 이름 · 주소
        sellerApplyService.resubmit(USER_ID, 27L, resubmitStore(" 리넨랩 성수 ", "linen-lab-seongsu"));

        // then
        assertThat(application.getStoreName()).isEqualTo("리넨랩 성수");
        assertThat(application.getSlug()).isEqualTo("linen-lab-seongsu");
        then(storeApplicationRepository).should().existsBySlugAndStatusInAndIdNot("linen-lab-seongsu", SellerApplyService.IN_PROGRESS, 27L);
        ArgumentCaptor<ApplicationHistory> history = ArgumentCaptor.forClass(ApplicationHistory.class);
        verify(applicationHistoryRepository).save(history.capture());
        assertThat(history.getValue().getReason()).isEqualTo("재제출 — 수정: 스토어명 · 스토어 주소");
    }

    @Test
    @DisplayName("스토어명 · 주소를 지금 값 그대로 보내면 중복 검사를 하지 않는다(자기 이름과 겹쳐 막히지 않게)")
    void resubmit_sameStoreIdentity_skipsDuplicateCheck() {
        // given
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(supplemented(List.of())));

        // when
        sellerApplyService.resubmit(USER_ID, 27L, resubmitStore("Linen Lab", "linen-lab"));

        // then
        verify(storeRepository, never()).existsBySlug(anyString());
        verify(storeRepository, never()).existsByNormalizedStoreName(anyString());
    }

    @Test
    @DisplayName("이력 사유 — 서류만 · 수정만 · 둘 다 · 아무것도 없음")
    void resubmitSummary_formats() {
        assertThat(SellerApplyService.resubmitSummary(List.of(DocType.BUSINESS_REG, DocType.PRODUCT_PHOTO), List.of()))
                .isEqualTo("재제출 — 서류: 사업자등록증 · 대표 상품 사진");
        assertThat(SellerApplyService.resubmitSummary(List.of(), List.of("상호"))).isEqualTo("재제출 — 수정: 상호");
        assertThat(SellerApplyService.resubmitSummary(List.of(), List.of())).isEqualTo("재제출");
    }

    private void assertResubmitInvalid(List<DocumentItem> documents, String messagePart) {
        assertThatThrownBy(() -> sellerApplyService.resubmit(USER_ID, 27L, resubmit(documents, null, null)))
                .hasMessageContaining(messagePart)
                .extracting("errorCode").isEqualTo(ApplyErrorCode.APPLY_DOCUMENT_INVALID);
    }

    /** 이 테스트 파일의 신청 본문(request)으로 만든 심사대기 신청 — id 27 · 신청자 4 */
    private static StoreApplication application() {
        SellerApplyRequest r = request(documents());
        StoreApplication application = StoreApplication.builder().userId(USER_ID).categoryId(r.categoryId()).bizType(r.bizType())
                .companyName(r.companyName()).ownerName(r.ownerName()).bizRegNo(r.bizRegNo()).ecommerceRegNo(r.ecommerceRegNo())
                .managerPhone(r.managerPhone()).managerEmail(r.managerEmail()).businessAddress(r.businessAddress())
                .storeName(r.storeName()).slug(r.slug()).intro(r.intro()).representativeProducts(r.toRepresentativeProducts())
                .bankName("국민").accountNo("000000-00-000000").accountHolder("홍길동")
                .submittedAt(NOW.minusDays(3)).build();
        ReflectionTestUtils.setField(application, "id", 27L);
        return application;
    }

    /** 이틀 전 보완요청 — 기한까지 5일 남음 */
    private static StoreApplication supplemented(List<DocType> requestedDocs) {
        StoreApplication application = application();
        application.requestSupplement("보완해 주세요", requestedDocs, NOW.minusDays(2));
        return application;
    }

    private static ApplicationResubmitRequest emptyResubmit() {
        return resubmit(null, null, null);
    }

    private static ApplicationResubmitRequest resubmit(List<DocumentItem> documents, String companyName, SettlementAccount account) {
        return new ApplicationResubmitRequest(null, null, null, null, null, companyName, null, null, null, null, null, null, null, account, documents);
    }

    private static ApplicationResubmitRequest resubmitStore(String storeName, String slug) {
        return new ApplicationResubmitRequest(null, null, null, storeName, slug, null, null, null, null, null, null, null, null,
                null, null);
    }

    private static ApplicationResubmitRequest resubmitBusiness(BizType bizType, String ownerName, String bizRegNo,
            List<DocumentItem> documents) {
        return new ApplicationResubmitRequest(bizType, ownerName, bizRegNo, null, null, null, null, null, null, null, null, null, null, null,
                documents);
    }

    private void assertDocumentInvalid(List<DocumentItem> documents, String messagePart) {
        assertThatThrownBy(() -> sellerApplyService.apply(USER_ID, request(documents)))
                .hasMessageContaining(messagePart)
                .extracting("errorCode").isEqualTo(ApplyErrorCode.APPLY_DOCUMENT_INVALID);
        verify(storeApplicationRepository, never()).save(any());
    }

    private void givenFilesUploaded() {
        given(sellerDocumentClient.head(anyString())).willAnswer(invocation ->
                Optional.of(invocation.<String>getArgument(0).endsWith(".pdf") ? PDF : JPG));
    }

    private void givenRejectedAt(LocalDateTime rejectedAt) {
        StoreApplication rejected = StoreApplication.builder().submittedAt(rejectedAt).build();
        given(storeApplicationRepository.findFirstByUserIdAndStatusOrderByStatusChangedAtDesc(eq(USER_ID), eq(ApplicationStatus.REJECTED)))
                .willReturn(Optional.of(rejected));
    }

    private static List<DocumentItem> documents() {
        return List.of(
                doc(DocType.BUSINESS_REG, "biz.pdf"),
                doc(DocType.ECOMMERCE_REG, "ecom.pdf"),
                doc(DocType.BANK_COPY, "bank.pdf"),
                doc(DocType.PRODUCT_PHOTO, "photo1.jpg"),
                doc(DocType.PRODUCT_PHOTO, "photo2.jpg"),
                doc(DocType.PRODUCT_PHOTO, "photo3.jpg"));
    }

    private static DocumentItem doc(DocType docType, String fileName) {
        return new DocumentItem(docType, "applications/" + USER_ID + "/" + fileName, fileName);
    }

    private static SellerApplyRequest request(List<DocumentItem> documents) {
        return request(documents, "<p>리넨 의류를 만듭니다.</p>");
    }

    private static SellerApplyRequest request(List<DocumentItem> documents, String intro) {
        return new SellerApplyRequest(BizType.INDIVIDUAL, "리넨랩", "홍길동", "000-00-00099", "2026-서울성동-0000",
                "010-0000-1234", "seller@example.com", "서울시 성동구", "Linen Lab", "linen-lab", 1L, intro,
                List.of(new ProductItem("리넨 셔츠", "셔츠", 39000L), new ProductItem("리넨 팬츠", "팬츠", 45000L)),
                ExpectedMonthlySales.FROM_5M_TO_20M, null, documents,
                new SettlementAccount("국민", "000000-00-000000", "홍길동"),
                new Agreements(true, true, true, null));
    }
}
