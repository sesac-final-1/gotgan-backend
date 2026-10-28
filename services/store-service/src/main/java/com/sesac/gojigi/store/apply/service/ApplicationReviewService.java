package com.sesac.gojigi.store.apply.service;

import com.sesac.gojigi.store.apply.dto.response.ApplicationApproveResponse;
import com.sesac.gojigi.store.apply.entity.ActorType;
import com.sesac.gojigi.store.apply.entity.ApplicationHistory;
import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.entity.StoreApplication;
import com.sesac.gojigi.store.apply.exception.ApplyErrorCode;
import com.sesac.gojigi.store.apply.exception.ApplyException;
import com.sesac.gojigi.store.apply.repository.ApplicationHistoryRepository;
import com.sesac.gojigi.store.apply.repository.StoreApplicationRepository;
import com.sesac.gojigi.store.store.entity.Store;
import com.sesac.gojigi.store.store.entity.StoreActorType;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.entity.StoreStatusHistory;
import com.sesac.gojigi.store.store.repository.StoreRepository;
import com.sesac.gojigi.store.store.repository.StoreStatusHistoryRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 입점 심사 — 승인 (docs/api/store.md 5절). 반려 · 보완요청도 이 서비스에 붙인다.
 * 동시 처리는 미리 검사한 뒤에도 저장 순간에 다시 걸릴 수 있어, saveAndFlush 로 그 자리에서 실행해 보고 409 로 바꾼다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationReviewService {

    private final StoreApplicationRepository storeApplicationRepository;
    private final ApplicationHistoryRepository applicationHistoryRepository;
    private final StoreRepository storeRepository;
    private final StoreStatusHistoryRepository storeStatusHistoryRepository;
    private final Clock clock;

    /** 신청서로 스토어(ACTIVE)를 만들고 신청을 APPROVED 로 — 스토어 · 두 이력 · 신청 상태를 한 트랜잭션으로 */
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

        log.info("입점 승인 - applicationId={}, storeId={}, userId={}", applicationId, store.getId(), application.getUserId());
        return ApplicationApproveResponse.of(applicationId, application.getStatus(), store.getId(), now);
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
