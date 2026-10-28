package com.sesac.gojigi.store.apply.service;

import com.sesac.gojigi.store.apply.client.SellerDocumentClient;
import com.sesac.gojigi.store.apply.client.SellerDocumentClient.PresignedDownload;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationDetailResponse;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationDetailResponse.Document;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationDetailResponse.History;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationDetailResponse.SameApplication;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationDetailResponse.SameBizRegNo;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationDetailResponse.SameStore;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationDetailResponse.SettlementAccount;
import com.sesac.gojigi.store.apply.dto.response.InternalApplicationSummaryResponse;
import com.sesac.gojigi.store.apply.dto.response.SellerApplyResponse;
import com.sesac.gojigi.store.apply.entity.ApplicationDocument;
import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.entity.StoreApplication;
import com.sesac.gojigi.store.apply.exception.ApplyErrorCode;
import com.sesac.gojigi.store.apply.exception.ApplyException;
import com.sesac.gojigi.store.apply.repository.ApplicationDocumentRepository;
import com.sesac.gojigi.store.apply.repository.ApplicationHistoryRepository;
import com.sesac.gojigi.store.apply.repository.StoreApplicationRepository;
import com.sesac.gojigi.store.store.entity.Store;
import com.sesac.gojigi.store.store.repository.StoreRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 입점 심사 조회 (담당 박지연, docs/api/store.md 3 · 4절) */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ApplicationQueryService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final StoreApplicationRepository storeApplicationRepository;
    private final ApplicationDocumentRepository applicationDocumentRepository;
    private final ApplicationHistoryRepository applicationHistoryRepository;
    private final StoreRepository storeRepository;
    private final SellerDocumentClient sellerDocumentClient;

    /** 페이징 없이 전체 — 관리자 화면이 전체를 받아 탭 건수 · 검색 · 정렬을 한다 (컨벤션 4-7). status 가 null 이면 전체 */
    public List<InternalApplicationSummaryResponse> getList(ApplicationStatus status) {
        List<StoreApplication> applications = status == null
                ? storeApplicationRepository.findAllByOrderByTermsAgreedAtDescIdDesc()
                : storeApplicationRepository.findAllByStatusOrderByTermsAgreedAtDescIdDesc(status);
        return applications.stream().map(InternalApplicationSummaryResponse::from).toList();
    }

    /** 상태와 상관없이 상세, 없으면 APPLY_NOT_FOUND. 서류 열람 주소 발급이 실패해도 상세는 돌려준다(url null) */
    public InternalApplicationDetailResponse getDetail(Long applicationId) {
        StoreApplication application = storeApplicationRepository.findById(applicationId)
                .orElseThrow(() -> new ApplyException(ApplyErrorCode.APPLY_NOT_FOUND));
        Long storeId = storeRepository.findByStoreApplicationId(applicationId).map(Store::getId).orElse(null);

        return new InternalApplicationDetailResponse(
                application.getId(),
                SellerApplyResponse.applicationNo(application.getId(), application.getTermsAgreedAt()),
                application.getStatus(),
                application.getStatusReason(),
                kst(application.getTermsAgreedAt()),
                kst(application.getStatusChangedAt()),
                storeId,
                application.getBizType(),
                application.getCompanyName(),
                application.getOwnerName(),
                application.getBizRegNo(),
                application.getEcommerceRegNo(),
                application.getManagerPhone(),
                application.getManagerEmail(),
                application.getBusinessAddress(),
                application.getStoreName(),
                application.getSlug(),
                application.getCategoryId(),
                application.getIntro(),
                application.getRepresentativeProducts(),
                application.getExpectedMonthlySales(),
                application.getSalesChannel(),
                new SettlementAccount(application.getBankName(), application.getAccountNo(), application.getAccountHolder()),
                application.isMarketingAgreed(),
                kst(application.getTermsAgreedAt()),
                documents(applicationId),
                applicationHistoryRepository.findAllByStoreApplicationIdOrderByIdAsc(applicationId).stream()
                        .map(h -> new History(h.getFromStatus(), h.getToStatus(), h.getActorType(), h.getReason(),
                                kst(h.getCreatedAt())))
                        .toList(),
                sameBizRegNo(application));
    }

    /** 종류별 · 올린 순서. 서명 실패 원인은 자격 증명뿐이라 한 번 실패하면 나머지도 실패 → 첫 실패에서 멈춰 경고를 한 줄만 남긴다 */
    private List<Document> documents(Long applicationId) {
        List<ApplicationDocument> documents = new ArrayList<>(applicationDocumentRepository.findAllByStoreApplicationId(applicationId));
        documents.sort(Comparator.comparing(ApplicationDocument::getDocType).thenComparing(ApplicationDocument::getId));

        List<Document> result = new ArrayList<>(documents.size());
        boolean signable = true;
        for (ApplicationDocument document : documents) {
            Optional<PresignedDownload> presigned = signable ? sellerDocumentClient.presignDownload(document.getS3Key()) : Optional.empty();
            signable = presigned.isPresent();
            result.add(new Document(document.getId(), document.getDocType(), document.getOriginalName(),
                    document.getContentType(), document.getFileSize(), document.isCurrent(), kst(document.getCreatedAt()),
                    presigned.map(PresignedDownload::url).orElse(null),
                    presigned.map(p -> kst(p.expiresAt())).orElse(null)));
        }
        return result;
    }

    /** store 테이블에는 사업자번호가 없어, 같은 번호의 다른 신청을 찾고 그 신청으로 만든 스토어를 찾는다 */
    private SameBizRegNo sameBizRegNo(StoreApplication application) {
        List<StoreApplication> others = storeApplicationRepository
                .findAllByBizRegNoAndIdNotOrderByTermsAgreedAtDescIdDesc(application.getBizRegNo(), application.getId());
        List<SameStore> stores = others.isEmpty() ? List.of()
                : storeRepository.findAllByStoreApplicationIdInAndDeletedAtIsNullOrderByIdAsc(
                                others.stream().map(StoreApplication::getId).toList()).stream()
                        .map(s -> new SameStore(s.getId(), s.getStoreName(), s.getStatus()))
                        .toList();
        return new SameBizRegNo(
                others.stream()
                        .map(a -> new SameApplication(a.getId(),
                                SellerApplyResponse.applicationNo(a.getId(), a.getTermsAgreedAt()),
                                a.getStoreName(), a.getStatus(), kst(a.getTermsAgreedAt())))
                        .toList(),
                stores);
    }

    private static OffsetDateTime kst(LocalDateTime dateTime) {
        return dateTime.atZone(KST).toOffsetDateTime();
    }

    private static OffsetDateTime kst(Instant instant) {
        return instant.atZone(KST).toOffsetDateTime();
    }
}
