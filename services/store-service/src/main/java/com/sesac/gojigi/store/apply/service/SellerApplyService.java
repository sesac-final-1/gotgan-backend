package com.sesac.gojigi.store.apply.service;

import com.sesac.gojigi.store.apply.client.SellerDocumentClient.FileMeta;
import com.sesac.gojigi.store.apply.client.SellerDocumentClient;
import com.sesac.gojigi.store.apply.dto.request.ApplicationResubmitRequest;
import com.sesac.gojigi.store.apply.dto.request.SellerApplyRequest.DocumentItem;
import com.sesac.gojigi.store.apply.dto.request.SellerApplyRequest.SettlementAccount;
import com.sesac.gojigi.store.apply.dto.request.SellerApplyRequest;
import com.sesac.gojigi.store.apply.dto.response.ApplicationResubmitResponse;
import com.sesac.gojigi.store.apply.dto.response.SellerApplyResponse;
import com.sesac.gojigi.store.apply.entity.ActorType;
import com.sesac.gojigi.store.apply.entity.ApplicationCorrection;
import com.sesac.gojigi.store.apply.entity.ApplicationDocument;
import com.sesac.gojigi.store.apply.entity.ApplicationHistory;
import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.entity.DocType;
import com.sesac.gojigi.store.apply.entity.RepresentativeProduct;
import com.sesac.gojigi.store.apply.entity.StoreApplication;
import com.sesac.gojigi.store.apply.exception.ApplyErrorCode;
import com.sesac.gojigi.store.apply.exception.ApplyException;
import com.sesac.gojigi.store.apply.repository.ApplicationDocumentRepository;
import com.sesac.gojigi.store.apply.repository.ApplicationHistoryRepository;
import com.sesac.gojigi.store.apply.repository.StoreApplicationRepository;
import com.sesac.gojigi.store.common.html.IntroHtml;
import com.sesac.gojigi.store.store.repository.StoreRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 입점 신청 · 보완 재제출 (STR-01 · 02 · 09 · 11, docs/api/store.md 2 · 2-3절). 담당 박지연.
 * 검사는 계약 순서대로 — DB 검사 → 서류 규칙 → S3 확인 — 하고, 저장만 트랜잭션으로 묶는다.
 * S3 HeadObject(서류 최대 13개)를 트랜잭션 밖에서 불러 느린 S3 가 DB 커넥션을 붙잡지 않게 한다.
 * 보완 재제출(2-3절)도 같은 서류 검사를 쓴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerApplyService {

    static final List<ApplicationStatus> IN_PROGRESS = List.of(ApplicationStatus.PENDING, ApplicationStatus.SUPPLEMENT);
    static final int REAPPLY_RESTRICTION_DAYS = 30;
    static final int MIN_PRODUCT_PHOTOS = 3;
    static final int MAX_PRODUCT_PHOTOS = 10;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

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
        checkCounts(items, EnumSet.allOf(DocType.class));
        return verifyFiles(userId, items);
    }

    /** {@code types} 의 종류마다 개수 — 한 장 서류는 1개, 상품 사진은 3~10장 */
    private static void checkCounts(List<DocumentItem> items, Set<DocType> types) {
        Map<DocType, Integer> counts = new EnumMap<>(DocType.class);
        items.forEach(item -> counts.merge(item.docType(), 1, Integer::sum));
        for (DocType type : types) {
            int count = counts.getOrDefault(type, 0);
            if (type != DocType.PRODUCT_PHOTO && count != 1) {
                throw invalidDocument(label(type) + "은 1개를 올려 주세요.");
            }
            if (type == DocType.PRODUCT_PHOTO && (count < MIN_PRODUCT_PHOTOS || count > MAX_PRODUCT_PHOTOS)) {
                throw invalidDocument("대표 상품 사진은 3~10장을 올려 주세요.");
            }
        }
    }

    /** 내 applications/{userId}/ 키 · 중복 확인 뒤 파일마다 S3 확인 */
    private List<VerifiedDocument> verifyFiles(Long userId, List<DocumentItem> items) {
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
                .intro(IntroHtml.sanitize(request.intro()))
                .representativeProducts(request.toRepresentativeProducts())
                .expectedMonthlySales(request.expectedMonthlySales())
                .salesChannel(blankToNull(request.salesChannel()))
                .bankName(request.settlementAccount().bank().trim())
                .accountNo(request.settlementAccount().number().trim())
                .accountHolder(request.settlementAccount().holder().trim())
                .marketingAgreed(request.agreements().marketingAgreed())
                .submittedAt(now)
                .build());

        applicationDocumentRepository.saveAll(documents.stream().map(d -> toDocument(application.getId(), d)).toList());
        applicationHistoryRepository.save(
                ApplicationHistory.of(application.getId(), null, ApplicationStatus.PENDING, ActorType.APPLICANT, null));
        return application;
    }

    /**
     * 보완 재제출 (2-3절, STR-09) — 내 보완요청 중 신청만. 상태 · 서류 구성 → S3 확인(트랜잭션 밖) → 저장 순서.
     * 저장 순간에 자동 반려 · 관리자 처리가 먼저 끝났으면 @Version 이 달라 409 (스펙 STR-09 — 진 쪽은 실패)
     */
    public ApplicationResubmitResponse resubmit(Long userId, Long applicationId, ApplicationResubmitRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        StoreApplication application = findOwn(userId, applicationId);
        application.requireResubmittable(now);
        List<DocumentItem> items = request.documentsOrEmpty();
        checkResubmitDocuments(application.getRequestedDocs(), items, changesBusinessIdentity(application, request));
        List<VerifiedDocument> documents = verifyFiles(userId, items);

        transactionTemplate.executeWithoutResult(status -> saveResubmit(applicationId, request, documents, now));
        log.info("입점 보완 재제출 - userId={}, applicationId={}, docTypes={}", userId, applicationId,
                items.stream().map(DocumentItem::docType).distinct().toList());
        return new ApplicationResubmitResponse(applicationId, ApplicationStatus.PENDING, now.atZone(KST).toOffsetDateTime());
    }

    /** 남의 신청은 없는 것과 같이 404 — 있는지 알려 주지 않는다 */
    private StoreApplication findOwn(Long userId, Long applicationId) {
        return storeApplicationRepository.findById(applicationId)
                .filter(application -> application.getUserId().equals(userId))
                .orElseThrow(() -> new ApplyException(ApplyErrorCode.APPLY_NOT_FOUND));
    }

    /** 사업자 구분 · 대표자 · 사업자등록번호가 실제로 바뀌는지 — 바뀌면 사업자등록증으로 다시 확인해야 한다 */
    private static boolean changesBusinessIdentity(StoreApplication current, ApplicationResubmitRequest request) {
        return (request.bizType() != null && request.bizType() != current.getBizType())
                || (request.ownerName() != null && !request.ownerName().trim().equals(current.getOwnerName()))
                || (request.bizRegNo() != null && !request.bizRegNo().equals(current.getBizRegNo()));
    }

    /**
     * 요청 서류는 모두 · 요청 밖 종류는 안 됨, 낸 종류마다 개수 규칙. V5 전 보완요청(요청 서류 기록 없음)은 낸 종류만 본다.
     * 사업자 정보를 바꾸면 사업자등록증이 필수 — 보완요청에 없던 서류여도 받는다
     */
    private static void checkResubmitDocuments(List<DocType> requestedDocs, List<DocumentItem> items,
            boolean businessIdentityChanged) {
        Set<DocType> submitted = items.stream().map(DocumentItem::docType)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DocType.class)));
        if (businessIdentityChanged && !submitted.contains(DocType.BUSINESS_REG)) {
            throw invalidDocument("사업자 구분 · 대표자 · 사업자등록번호를 고치면 사업자등록증도 다시 올려 주세요.");
        }
        if (requestedDocs != null) {
            for (DocType requested : requestedDocs) {
                if (!submitted.contains(requested)) {
                    throw invalidDocument(label(requested) + "을 다시 올려 주세요.");
                }
            }
            for (DocType type : submitted) {
                if (!requestedDocs.contains(type) && !(businessIdentityChanged && type == DocType.BUSINESS_REG)) {
                    throw invalidDocument("보완 요청받지 않은 서류입니다: " + label(type));
                }
            }
        }
        checkCounts(items, submitted);
    }

    private void saveResubmit(Long applicationId, ApplicationResubmitRequest request, List<VerifiedDocument> documents,
            LocalDateTime now) {
        StoreApplication application = storeApplicationRepository.findById(applicationId).orElseThrow(); // 앞에서 확인
        List<String> changed = new ArrayList<>();
        application.resubmit(correction(application, request, changed), now);
        try {
            storeApplicationRepository.saveAndFlush(application);
        } catch (OptimisticLockingFailureException e) {
            throw new ApplyException(ApplyErrorCode.APPLY_INVALID_STATUS, "신청 상태가 바뀌었습니다. 다시 확인해 주세요.");
        }

        List<DocType> types = documents.stream().map(d -> d.item().docType()).distinct().toList();
        if (!types.isEmpty()) {
            applicationDocumentRepository.findAllByStoreApplicationIdAndCurrentTrueAndDocTypeIn(applicationId, types)
                    .forEach(ApplicationDocument::replace);
            applicationDocumentRepository.saveAll(documents.stream().map(d -> toDocument(applicationId, d)).toList());
        }
        applicationHistoryRepository.save(ApplicationHistory.of(applicationId, ApplicationStatus.SUPPLEMENT,
                ApplicationStatus.PENDING, ActorType.APPLICANT, resubmitSummary(types, changed)));
    }

    /** 보내지 않은 항목은 지금 값 — 실제로 바뀐 항목 이름을 {@code changed} 에 모은다(이력 사유로 관리자에게 보임) */
    private static ApplicationCorrection correction(StoreApplication current, ApplicationResubmitRequest request,
            List<String> changed) {
        SettlementAccount account = request.settlementAccount();
        String intro = request.intro() == null ? current.getIntro() : IntroHtml.sanitize(request.intro());
        List<RepresentativeProduct> products = request.representativeProducts() == null ? current.getRepresentativeProducts()
                : SellerApplyRequest.toRepresentativeProducts(request.representativeProducts());
        ApplicationCorrection correction = new ApplicationCorrection(
                request.bizType() == null ? current.getBizType() : request.bizType(),
                trimOr(request.ownerName(), current.getOwnerName()),
                request.bizRegNo() == null ? current.getBizRegNo() : request.bizRegNo(),
                trimOr(request.companyName(), current.getCompanyName()),
                trimOr(request.ecommerceRegNo(), current.getEcommerceRegNo()),
                trimOr(request.managerPhone(), current.getManagerPhone()),
                trimOr(request.managerEmail(), current.getManagerEmail()),
                trimOr(request.businessAddress(), current.getBusinessAddress()),
                request.categoryId() == null ? current.getCategoryId() : request.categoryId(),
                intro,
                products,
                account == null ? current.getBankName() : account.bank().trim(),
                account == null ? current.getAccountNo() : account.number().trim(),
                account == null ? current.getAccountHolder() : account.holder().trim());

        addIfChanged(changed, "사업자 구분", current.getBizType(), correction.bizType());
        addIfChanged(changed, "대표자", current.getOwnerName(), correction.ownerName());
        addIfChanged(changed, "사업자등록번호", current.getBizRegNo(), correction.bizRegNo());
        addIfChanged(changed, "상호", current.getCompanyName(), correction.companyName());
        addIfChanged(changed, "통신판매업 신고번호", current.getEcommerceRegNo(), correction.ecommerceRegNo());
        addIfChanged(changed, "담당자 휴대폰", current.getManagerPhone(), correction.managerPhone());
        addIfChanged(changed, "담당자 이메일", current.getManagerEmail(), correction.managerEmail());
        addIfChanged(changed, "사업장 주소", current.getBusinessAddress(), correction.businessAddress());
        addIfChanged(changed, "카테고리", current.getCategoryId(), correction.categoryId());
        addIfChanged(changed, "스토어 소개", current.getIntro(), correction.intro());
        addIfChanged(changed, "대표 상품", current.getRepresentativeProducts(), correction.representativeProducts());
        addIfChanged(changed, "정산 계좌", List.of(current.getBankName(), current.getAccountNo(), current.getAccountHolder()),
                List.of(correction.bankName(), correction.accountNo(), correction.accountHolder()));
        return correction;
    }

    /** 이력 사유 — 예: "재제출 — 서류: 통신판매업 신고증 / 수정: 상호 · 정산 계좌" (255자 안) */
    static String resubmitSummary(List<DocType> docTypes, List<String> changedFields) {
        StringBuilder summary = new StringBuilder("재제출");
        String separator = " — ";
        if (!docTypes.isEmpty()) {
            summary.append(separator).append("서류: ").append(docTypes.stream().map(DocumentRules::label).collect(Collectors.joining(" · ")));
            separator = " / ";
        }
        if (!changedFields.isEmpty()) {
            summary.append(separator).append("수정: ").append(String.join(" · ", changedFields));
        }
        return summary.toString();
    }

    private static void addIfChanged(List<String> changed, String name, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changed.add(name);
        }
    }

    private static String trimOr(String requested, String current) {
        return requested == null ? current : requested.trim();
    }

    private static ApplicationDocument toDocument(Long applicationId, VerifiedDocument document) {
        return ApplicationDocument.of(applicationId, document.item().docType(), document.item().key(),
                document.item().originalName(), document.meta().contentType(), (int) document.meta().size());
    }

    /** 스토어명 중복 비교 규칙 — 공백 제거 + 소문자 (저장소 쿼리의 lower(replace(name, ' ', '')) 와 같게) */
    static String normalizeStoreName(String storeName) {
        return storeName.replace(" ", "").toLowerCase(Locale.ROOT);
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
