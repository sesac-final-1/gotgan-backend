package com.sesac.gojigi.store.apply.service;

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
import com.sesac.gojigi.store.apply.entity.DocType;
import com.sesac.gojigi.store.apply.entity.StoreApplication;
import com.sesac.gojigi.store.apply.exception.ApplyErrorCode;
import com.sesac.gojigi.store.apply.exception.ApplyException;
import com.sesac.gojigi.store.apply.repository.ApplicationHistoryRepository;
import com.sesac.gojigi.store.apply.repository.StoreApplicationRepository;
import com.sesac.gojigi.store.common.event.EventOutboxService;
import com.sesac.gojigi.store.store.entity.Store;
import com.sesac.gojigi.store.store.entity.StoreActorType;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.entity.StoreStatusHistory;
import com.sesac.gojigi.store.store.repository.StoreRepository;
import com.sesac.gojigi.store.store.repository.StoreStatusHistoryRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 입점 심사 — 승인 · 반려 · 보완요청 (docs/api/store.md 5 · 6 · 7절).
 * 동시 처리는 미리 검사한 뒤에도 저장 순간에 다시 걸릴 수 있어, saveAndFlush 로 그 자리에서 실행해 보고 409 로 바꾼다.
 * 이벤트는 같은 트랜잭션에서 아웃박스에 남기고 커밋 뒤 발행한다 — 발행이 실패해도 승인은 그대로, 발행기가 다시 보낸다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationReviewService {

    static final String STORE_APPROVED = "StoreApproved";
    static final String STORE_APPLICATION_REJECTED = "StoreApplicationRejected";
    static final String STORE_APPLICATION_SUPPLEMENT_REQUESTED = "StoreApplicationSupplementRequested";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final StoreApplicationRepository storeApplicationRepository;
    private final ApplicationHistoryRepository applicationHistoryRepository;
    private final StoreRepository storeRepository;
    private final StoreStatusHistoryRepository storeStatusHistoryRepository;
    private final EventOutboxService eventOutboxService;
    private final Clock clock;

    /** 신청서로 스토어(ACTIVE)를 만들고 신청을 APPROVED 로 — 스토어 · 두 이력 · 신청 상태 · StoreApproved 아웃박스 행을 한 트랜잭션으로 */
    @Transactional
    public ApplicationApproveResponse approve(Long applicationId) {
        StoreApplication application = storeApplicationRepository.findById(applicationId)
                .orElseThrow(() -> new ApplyException(ApplyErrorCode.APPLY_NOT_FOUND));
        ApplicationStatus fromStatus = application.getStatus();
        LocalDateTime now = LocalDateTime.now(clock);

        application.approve(now); // 이미 처리된 신청이면 스토어 검사 전에 409
        checkStoreIdentity(application);
        flushApplication(application);
        Store store = createStore(application);

        storeStatusHistoryRepository.save(
                StoreStatusHistory.of(store.getId(), null, StoreStatus.ACTIVE, StoreActorType.ADMIN, null));
        applicationHistoryRepository.save(
                ApplicationHistory.of(applicationId, fromStatus, ApplicationStatus.APPROVED, ActorType.ADMIN, null));
        eventOutboxService.enqueue(STORE_APPROVED, String.valueOf(store.getId()), storeApprovedEventId(applicationId), now,
                payload(application, store));

        log.info("입점 승인 - applicationId={}, storeId={}, userId={}", applicationId, store.getId(), application.getUserId());
        return ApplicationApproveResponse.of(applicationId, application.getStatus(), store.getId(), now);
    }

    /** 신청을 REJECTED 로 + 이력(사유) + StoreApplicationRejected 아웃박스 행을 한 트랜잭션으로 */
    @Transactional
    public ApplicationRejectResponse reject(Long applicationId, String reason) {
        StoreApplication application = storeApplicationRepository.findById(applicationId)
                .orElseThrow(() -> new ApplyException(ApplyErrorCode.APPLY_NOT_FOUND));
        ApplicationStatus fromStatus = application.getStatus();
        LocalDateTime now = LocalDateTime.now(clock);

        application.reject(reason, now);
        flushApplication(application);

        applicationHistoryRepository.save(
                ApplicationHistory.of(applicationId, fromStatus, ApplicationStatus.REJECTED, ActorType.ADMIN, reason));
        // 반려는 신청 하나에 한 번뿐(REJECTED 에서 다른 상태로 가지 않음) → 승인과 같이 신청 ID 로 eventId 고정
        eventOutboxService.enqueue(STORE_APPLICATION_REJECTED, String.valueOf(applicationId),
                applicationEventId(STORE_APPLICATION_REJECTED, applicationId), now,
                new StoreApplicationRejectedPayload(applicationId, application.getUserId(), application.getStoreName(),
                        application.getManagerEmail(), reason, kst(now)));

        log.info("입점 반려 - applicationId={}, userId={}", applicationId, application.getUserId());
        return ApplicationRejectResponse.of(applicationId, application.getStatus(), now);
    }

    /** 신청을 SUPPLEMENT 로 + 이력(메시지) + StoreApplicationSupplementRequested 아웃박스 행을 한 트랜잭션으로. requestedDocs 는 저장하지 않음 */
    @Transactional
    public ApplicationSupplementResponse requestSupplement(Long applicationId, String message, List<DocType> requestedDocs) {
        if (new HashSet<>(requestedDocs).size() != requestedDocs.size()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "보완 요청 서류가 중복됐습니다.");
        }
        StoreApplication application = storeApplicationRepository.findById(applicationId)
                .orElseThrow(() -> new ApplyException(ApplyErrorCode.APPLY_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now(clock);

        application.requestSupplement(message, now);
        flushApplication(application);
        LocalDateTime dueAt = application.supplementDueAt();

        applicationHistoryRepository.save(ApplicationHistory.of(applicationId, ApplicationStatus.PENDING,
                ApplicationStatus.SUPPLEMENT, ActorType.ADMIN, message));
        // 보완 → 재제출 → 다시 보완으로 한 신청에 여러 번 생길 수 있어 신청 ID 로 고정하지 않는다 — 다시 보낼 때는 아웃박스에 저장된 값
        eventOutboxService.enqueue(STORE_APPLICATION_SUPPLEMENT_REQUESTED, String.valueOf(applicationId),
                UUID.randomUUID().toString(), now,
                new StoreApplicationSupplementRequestedPayload(applicationId, application.getUserId(),
                        application.getStoreName(), application.getManagerEmail(),
                        requestedDocs.stream().map(DocType::getLabel).toList(), message, kst(dueAt)));

        log.info("입점 보완요청 - applicationId={}, userId={}, requestedDocs={}", applicationId, application.getUserId(),
                requestedDocs);
        return new ApplicationSupplementResponse(applicationId, application.getStatus(), kst(dueAt));
    }

    private static OffsetDateTime kst(LocalDateTime dateTime) {
        return dateTime.atZone(KST).toOffsetDateTime();
    }

    /** StoreApproved:{applicationId} 의 이름 기반 UUID (v3) — 신청 하나에 항상 같은 값 (store.md 5절, 노션 10/23 user 요청) */
    static String storeApprovedEventId(Long applicationId) {
        return applicationEventId(STORE_APPROVED, applicationId);
    }

    /** {eventType}:{applicationId} 의 이름 기반 UUID (v3) — 신청 하나에 한 번만 생기는 이벤트용 */
    static String applicationEventId(String eventType, Long applicationId) {
        return UUID.nameUUIDFromBytes((eventType + ":" + applicationId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static StoreApprovedPayload payload(StoreApplication application, Store store) {
        return new StoreApprovedPayload(store.getId(), application.getUserId(), store.getStoreName(),
                application.getManagerEmail(), new StoreApprovedPayload.SettlementAccount(
                        application.getBankName(), application.getAccountNo(), application.getAccountHolder()));
    }

    /** 신청 이후 같은 회원 · 주소 · 스토어명의 스토어가 생겼는지 — 스토어(폐점 · 소프트 삭제 포함)만 본다 */
    private void checkStoreIdentity(StoreApplication application) {
        if (storeRepository.existsByUserId(application.getUserId())) {
            throw new ApplyException(ApplyErrorCode.APPLY_STORE_ALREADY_EXISTS);
        }
        if (storeRepository.existsBySlug(application.getSlug())) {
            throw new ApplyException(ApplyErrorCode.APPLY_SLUG_DUPLICATED);
        }
        if (storeRepository.existsByNormalizedStoreName(SellerApplyService.normalizeStoreName(application.getStoreName()))) {
            throw new ApplyException(ApplyErrorCode.APPLY_STORE_NAME_DUPLICATED);
        }
    }

    /** 신청 UPDATE 를 바로 실행 — 다른 관리자의 승인 · 반려가 먼저 저장됐으면 @Version 이 달라 실패한다 */
    private void flushApplication(StoreApplication application) {
        try {
            storeApplicationRepository.saveAndFlush(application);
        } catch (OptimisticLockingFailureException e) {
            throw new ApplyException(ApplyErrorCode.APPLY_INVALID_STATUS);
        }
    }

    /** 스토어 INSERT 를 바로 실행 — 검사 뒤 다른 승인이 같은 값으로 먼저 저장했으면 UNIQUE 제약 이름으로 코드를 고른다 */
    private Store createStore(StoreApplication application) {
        Store store = Store.open(application.getId(), application.getUserId(), application.getStoreName(),
                application.getSlug(), application.getIntro(), application.getManagerPhone());
        try {
            return storeRepository.saveAndFlush(store);
        } catch (DataIntegrityViolationException e) {
            throw new ApplyException(duplicateCode(e));
        }
    }

    /** MySQL 메시지 예: Duplicate entry 'gotgan' for key 'store.uk_store_slug' */
    static ApplyErrorCode duplicateCode(DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("uk_store_slug")) {
            return ApplyErrorCode.APPLY_SLUG_DUPLICATED;
        }
        if (message.contains("uk_store_store_name")) {
            return ApplyErrorCode.APPLY_STORE_NAME_DUPLICATED;
        }
        if (message.contains("uk_store_user_id")) {
            return ApplyErrorCode.APPLY_STORE_ALREADY_EXISTS;
        }
        if (message.contains("uk_store_store_application_id")) {
            return ApplyErrorCode.APPLY_INVALID_STATUS; // 같은 신청을 동시에 두 번 승인
        }
        throw e; // 예상 밖 제약 위반은 그대로 500 — 원인을 로그에 남긴다
    }
}
