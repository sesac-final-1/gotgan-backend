package com.sesac.gojigi.store.apply.service;

import com.sesac.gojigi.store.apply.client.SellerDocumentClient;
import com.sesac.gojigi.store.apply.client.SellerDocumentClient.FileMeta;
import com.sesac.gojigi.store.apply.dto.request.SellerApplyRequest;
import com.sesac.gojigi.store.apply.dto.request.SellerApplyRequest.DocumentItem;
import com.sesac.gojigi.store.apply.dto.response.SellerApplyResponse;
import com.sesac.gojigi.store.apply.entity.ActorType;
import com.sesac.gojigi.store.apply.entity.ApplicationDocument;
import com.sesac.gojigi.store.apply.entity.ApplicationHistory;
import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.entity.DocType;
import com.sesac.gojigi.store.apply.entity.StoreApplication;
import com.sesac.gojigi.store.apply.exception.ApplyErrorCode;
import com.sesac.gojigi.store.apply.exception.ApplyException;
import com.sesac.gojigi.store.apply.repository.ApplicationDocumentRepository;
import com.sesac.gojigi.store.apply.repository.ApplicationHistoryRepository;
import com.sesac.gojigi.store.apply.repository.StoreApplicationRepository;
import com.sesac.gojigi.store.store.repository.StoreRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 입점 신청 (STR-01 · 02 · 11, docs/api/store.md 2절). 담당 박지연.
 * 검사는 계약 순서대로 — DB 검사 → 서류 규칙 → S3 확인 — 하고, 저장만 트랜잭션으로 묶는다.
 * S3 HeadObject(서류 최대 13개)를 트랜잭션 밖에서 불러 느린 S3 가 DB 커넥션을 붙잡지 않게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerApplyService {

    static final List<ApplicationStatus> IN_PROGRESS = List.of(ApplicationStatus.PENDING, ApplicationStatus.SUPPLEMENT);
    static final int REAPPLY_RESTRICTION_DAYS = 30;
    static final int MIN_PRODUCT_PHOTOS = 3;
    static final int MAX_PRODUCT_PHOTOS = 10;

    private static final PolicyFactory INTRO_POLICY = Sanitizers.FORMATTING.and(Sanitizers.BLOCKS).and(Sanitizers.LINKS);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final StoreRepository storeRepository;
    private final StoreApplicationRepository storeApplicationRepository;
    private final ApplicationDocumentRepository applicationDocumentRepository;
    private final ApplicationHistoryRepository applicationHistoryRepository;
    private final SellerDocumentClient sellerDocumentClient;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public SellerApplyResponse apply(Long userId, SellerApplyRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        checkApplicant(userId, now);
        checkStoreIdentity(request);
        List<VerifiedDocument> documents = verifyDocuments(userId, request.documents());

        StoreApplication application = transactionTemplate.execute(status -> save(userId, request, documents, now));
        log.info("입점 신청 접수 - userId={}, applicationId={}", userId, application.getId());
        return SellerApplyResponse.of(application.getId(), application.getStatus(), now);
    }

    /** 1인 1스토어(폐점 포함) → 진행 중 신청 → 반려 후 30일 */
    private void checkApplicant(Long userId, LocalDateTime now) {
        if (storeRepository.existsByUserId(userId)) {
            throw new ApplyException(ApplyErrorCode.APPLY_STORE_ALREADY_EXISTS);
        }
        if (storeApplicationRepository.existsByUserIdAndStatusIn(userId, IN_PROGRESS)) {
            throw new ApplyException(ApplyErrorCode.APPLY_IN_PROGRESS);
        }
        storeApplicationRepository.findFirstByUserIdAndStatusOrderByStatusChangedAtDesc(userId, ApplicationStatus.REJECTED)
                .map(rejected -> rejected.getStatusChangedAt().plusDays(REAPPLY_RESTRICTION_DAYS))
                .filter(reapplyAt -> now.isBefore(reapplyAt))
                .ifPresent(reapplyAt -> {
                    throw new ApplyException(ApplyErrorCode.APPLY_REAPPLY_RESTRICTED,
                            "반려 후 30일이 지나야 다시 신청할 수 있습니다. " + reapplyAt.format(DATE) + " 부터 신청할 수 있습니다.");
                });
    }

    /** slug · 스토어명 — 스토어(폐점 포함)와 진행 중 신청 모두와 겹치면 안 된다 */
    private void checkStoreIdentity(SellerApplyRequest request) {
        if (storeRepository.existsBySlug(request.slug())
                || storeApplicationRepository.existsBySlugAndStatusIn(request.slug(), IN_PROGRESS)) {
            throw new ApplyException(ApplyErrorCode.APPLY_SLUG_DUPLICATED);
        }
        String normalizedName = normalizeStoreName(request.storeName());
        if (storeRepository.existsByNormalizedStoreName(normalizedName)
                || storeApplicationRepository.existsByNormalizedStoreNameAndStatusIn(normalizedName, IN_PROGRESS)) {
            throw new ApplyException(ApplyErrorCode.APPLY_STORE_NAME_DUPLICATED);
        }
    }

    /** 개수 · 키 규칙을 먼저 보고(S3 호출 없음), 통과하면 파일마다 HeadObject 로 실제 형식 · 크기 확인 */
    private List<VerifiedDocument> verifyDocuments(Long userId, List<DocumentItem> items) {
        Map<DocType, Integer> counts = new EnumMap<>(DocType.class);
        items.forEach(item -> counts.merge(item.docType(), 1, Integer::sum));
        for (DocType single : List.of(DocType.BUSINESS_REG, DocType.ECOMMERCE_REG, DocType.BANK_COPY)) {
            if (counts.getOrDefault(single, 0) != 1) {
                throw invalidDocument(label(single) + "은 1개를 올려 주세요.");
            }
        }
        int photos = counts.getOrDefault(DocType.PRODUCT_PHOTO, 0);
        if (photos < MIN_PRODUCT_PHOTOS || photos > MAX_PRODUCT_PHOTOS) {
            throw invalidDocument("대표 상품 사진은 3~10장을 올려 주세요.");
        }

        String ownPrefix = "applications/" + userId + "/";
        Set<String> keys = new HashSet<>();
        for (DocumentItem item : items) {
            if (!item.key().startsWith(ownPrefix) || item.key().length() == ownPrefix.length()) {
                throw invalidDocument(label(item.docType()) + " 파일이 올바르지 않습니다. 다시 올려 주세요.");
            }
            if (!keys.add(item.key())) {
                throw invalidDocument("같은 파일을 두 번 올렸습니다: " + item.originalName());
            }
        }

        return items.stream().map(this::verify).toList();
    }

    private VerifiedDocument verify(DocumentItem item) {
        FileMeta meta = sellerDocumentClient.head(item.key())
                .orElseThrow(() -> invalidDocument(label(item.docType()) + " 파일이 업로드되지 않았습니다. 다시 올려 주세요."));
        if (!DocumentRules.allows(item.docType(), meta.contentType())) {
            throw invalidDocument(item.docType() == DocType.PRODUCT_PHOTO
                    ? "대표 상품 사진은 JPG · PNG 만 올릴 수 있습니다: " + item.originalName()
                    : label(item.docType()) + "은 PDF · JPG · PNG 만 올릴 수 있습니다.");
        }
        if (!DocumentRules.withinSize(meta.size())) {
            throw invalidDocument("파일은 10MB 이하만 올릴 수 있습니다: " + item.originalName());
        }
        return new VerifiedDocument(item, meta);
    }

    private StoreApplication save(Long userId, SellerApplyRequest request, List<VerifiedDocument> documents,
            LocalDateTime now) {
        StoreApplication application = storeApplicationRepository.save(StoreApplication.builder()
                .userId(userId)
                .categoryId(request.categoryId())
                .bizType(request.bizType())
                .companyName(request.companyName().trim())
                .ownerName(request.ownerName().trim())
                .bizRegNo(request.bizRegNo())
                .ecommerceRegNo(request.ecommerceRegNo().trim())
                .managerPhone(request.managerPhone())
                .managerEmail(request.managerEmail().trim())
                .businessAddress(request.businessAddress().trim())
                .storeName(request.storeName().trim())
                .slug(request.slug())
                .intro(sanitizeIntro(request.intro()))
                .representativeProducts(request.toRepresentativeProducts())
                .expectedMonthlySales(request.expectedMonthlySales())
                .salesChannel(blankToNull(request.salesChannel()))
                .bankName(request.settlementAccount().bank().trim())
                .accountNo(request.settlementAccount().number().trim())
                .accountHolder(request.settlementAccount().holder().trim())
                .marketingAgreed(request.agreements().marketingAgreed())
                .submittedAt(now)
                .build());

        applicationDocumentRepository.saveAll(documents.stream()
                .map(d -> ApplicationDocument.of(application.getId(), d.item().docType(), d.item().key(),
                        d.item().originalName(), d.meta().contentType(), (int) d.meta().size()))
                .toList());
        applicationHistoryRepository.save(
                ApplicationHistory.of(application.getId(), null, ApplicationStatus.PENDING, ActorType.APPLICANT, null));
        return application;
    }

    /** 스토어명 중복 비교 규칙 — 공백 제거 + 소문자 (저장소 쿼리의 lower(replace(name, ' ', '')) 와 같게) */
    static String normalizeStoreName(String storeName) {
        return storeName.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private static String sanitizeIntro(String intro) {
        String value = blankToNull(intro);
        return value == null ? null : blankToNull(INTRO_POLICY.sanitize(value));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ApplyException invalidDocument(String message) {
        return new ApplyException(ApplyErrorCode.APPLY_DOCUMENT_INVALID, message);
    }

    private static String label(DocType docType) {
        return DocumentRules.label(docType);
    }

    private record VerifiedDocument(DocumentItem item, FileMeta meta) {
    }
}
