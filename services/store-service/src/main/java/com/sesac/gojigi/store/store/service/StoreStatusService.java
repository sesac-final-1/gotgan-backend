package com.sesac.gojigi.store.store.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.store.common.event.EventOutboxService;
import com.sesac.gojigi.store.store.dto.event.StoreStatusChangedPayload;
import com.sesac.gojigi.store.store.dto.response.InternalStoreResponse;
import com.sesac.gojigi.store.store.entity.Store;
import com.sesac.gojigi.store.store.entity.StoreActorType;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.entity.StoreStatusHistory;
import com.sesac.gojigi.store.store.exception.StoreErrorCode;
import com.sesac.gojigi.store.store.exception.StoreException;
import com.sesac.gojigi.store.store.repository.StoreRepository;
import com.sesac.gojigi.store.store.repository.StoreStatusHistoryRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스토어 상태 변경 (담당 박지연) — 관리자 정지 · 해제 (docs/api/store.md 9절). 폐점(판매자)도 이 서비스에 붙인다.
 * 상태 · 이력 · StoreStatusChanged 아웃박스 행을 한 트랜잭션으로, 이벤트는 커밋 직후 발행(실패하면 발행기가 다시).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreStatusService {

    static final String STORE_STATUS_CHANGED = "StoreStatusChanged";

    private final StoreRepository storeRepository;
    private final StoreStatusHistoryRepository storeStatusHistoryRepository;
    private final EventOutboxService eventOutboxService;
    private final Clock clock;

    /** ACTIVE ↔ SUSPENDED 만. 이미 그 상태면 그대로 200(이력 · 이벤트 없음), 폐점 스토어는 409 */
    @Transactional
    public InternalStoreResponse changeByAdmin(Long storeId, StoreStatus target, String reason) {
        if (target != StoreStatus.ACTIVE && target != StoreStatus.SUSPENDED) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "정지(SUSPENDED) · 해제(ACTIVE)만 할 수 있습니다. 폐점은 판매자만 합니다.");
        }
        Store store = storeRepository.findForUpdate(storeId)
                .orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
        StoreStatus fromStatus = store.getStatus();

        if (store.changeStatusByAdmin(target, reason)) {
            storeStatusHistoryRepository.save(StoreStatusHistory.of(storeId, fromStatus, target, StoreActorType.ADMIN, reason));
            // 정지 · 해제는 한 스토어에 여러 번 생겨 매번 새 eventId — 다시 보낼 때는 아웃박스에 저장된 값
            eventOutboxService.enqueue(STORE_STATUS_CHANGED, String.valueOf(storeId), UUID.randomUUID().toString(),
                    LocalDateTime.now(clock), new StoreStatusChangedPayload(storeId, target));
            log.info("스토어 상태 변경 - storeId={}, {} → {}, actor=ADMIN", storeId, fromStatus, target);
        }
        return InternalStoreResponse.from(store);
    }
}
