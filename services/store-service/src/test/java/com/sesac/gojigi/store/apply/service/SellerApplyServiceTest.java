package com.sesac.gojigi.store.apply.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.store.apply.client.SellerDocumentClient;
import com.sesac.gojigi.store.apply.client.SellerDocumentClient.FileMeta;
import com.sesac.gojigi.store.apply.dto.request.SellerApplyRequest;
import com.sesac.gojigi.store.apply.dto.request.SellerApplyRequest.Agreements;
import com.sesac.gojigi.store.apply.dto.request.SellerApplyRequest.DocumentItem;
import com.sesac.gojigi.store.apply.dto.request.SellerApplyRequest.ProductItem;
import com.sesac.gojigi.store.apply.dto.request.SellerApplyRequest.SettlementAccount;
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
                applicationDocumentRepository, applicationHistoryRepository, sellerDocumentClient, transactionTemplate, clock);
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
