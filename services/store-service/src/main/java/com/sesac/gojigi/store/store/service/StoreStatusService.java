package com.sesac.gojigi.store.store.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.store.common.event.EventOutboxService;
import com.sesac.gojigi.store.store.client.OrderClientAdapter;
import com.sesac.gojigi.store.store.client.SettlementClientAdapter;
import com.sesac.gojigi.store.store.client.dto.SettlementOutstandingResponse;
import com.sesac.gojigi.store.store.dto.event.StoreStatusChangedPayload;
import com.sesac.gojigi.store.store.dto.response.InternalStoreResponse;
import com.sesac.gojigi.store.store.dto.response.StoreCloseResponse;
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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 스토어 상태 변경 (담당 박지연) — 관리자 정지 · 해제 (docs/api/store.md 9절) · 판매자 폐점 (12절).
 * 상태 · 이력 · StoreStatusChanged 아웃박스 행을 한 트랜잭션으로, 이벤트는 커밋 직후 발행(실패하면 발행기가 다시).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreStatusService {

    static final String STORE_STATUS_CHANGED = "StoreStatusChanged";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final StoreRepository storeRepository;
    private final StoreStatusHistoryRepository storeStatusHistoryRepository;
    private final EventOutboxService eventOutboxService;
    private final StoreQueryService storeQueryService;
    private final OrderClientAdapter orderClientAdapter;
    private final SettlementClientAdapter settlementClientAdapter;
    private final TransactionTemplate transactionTemplate;
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
        return storeQueryService.getInternal(storeId); // 바뀐 상태 · 새 이력까지 담은 1절 응답
    }

    /**
     * 판매자 폐점 (STR-13). 진행 중 주문(order) · 미정산(settlement)을 **잠금 · 트랜잭션 밖에서** 먼저 확인하고 — HTTP 호출 동안
     * 스토어 행을 잡고 있지 않게 — 통과하면 행을 잠가 CLOSED · 이력(SELLER) · StoreStatusChanged{CLOSED} 를 한 트랜잭션으로.
     * 이미 폐점이면 다른 서비스를 부르지 않고 그대로 응답(멱등). 두 서비스 장애 · 타임아웃이면 502 로 거절(어댑터 폴백).
     */
    public StoreCloseResponse closeBySeller(Long storeId) {
        Store store = storeRepository.findByIdAndDeletedAtIsNull(storeId)
                .orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
        if (store.getStatus() == StoreStatus.CLOSED) {
            return new StoreCloseResponse(storeId, StoreStatus.CLOSED, closedAt(storeId));
        }

        long inProgress = orderClientAdapter.inProgressCount(storeId);
        if (inProgress > 0) {
            throw new StoreException(StoreErrorCode.STORE_ORDERS_IN_PROGRESS,
                    "진행 중인 주문 %d건이 모두 끝난 뒤 폐점할 수 있습니다.".formatted(inProgress));
        }
        SettlementOutstandingResponse outstanding = settlementClientAdapter.outstanding(storeId);
        if (!outstanding.clear()) {
            throw new StoreException(StoreErrorCode.STORE_SETTLEMENT_OUTSTANDING,
                    "미정산 %,d원 — 지급 완료(매주 목요일) 후 폐점할 수 있습니다.".formatted(outstanding.outstandingAmt()));
        }

        return transactionTemplate.execute(tx -> {
            Store locked = storeRepository.findForUpdate(storeId)
                    .orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
            StoreStatus fromStatus = locked.getStatus();
            if (!locked.close()) {   // 확인하는 사이 같은 판매자의 다른 요청이 먼저 폐점
                return new StoreCloseResponse(storeId, StoreStatus.CLOSED, closedAt(storeId));
            }
            LocalDateTime now = LocalDateTime.now(clock);
            storeStatusHistoryRepository.save(StoreStatusHistory.of(storeId, fromStatus, StoreStatus.CLOSED, StoreActorType.SELLER, null));
            eventOutboxService.enqueue(STORE_STATUS_CHANGED, String.valueOf(storeId), UUID.randomUUID().toString(), now,
                    new StoreStatusChangedPayload(storeId, StoreStatus.CLOSED));
            log.info("스토어 폐점 - storeId={}, {} → CLOSED, actor=SELLER", storeId, fromStatus);
            return new StoreCloseResponse(storeId, StoreStatus.CLOSED, now.atZone(KST).toOffsetDateTime());
        });
    }

    /** 이미 폐점한 스토어의 폐점 시각 — 마지막 CLOSED 이력 */
    private OffsetDateTime closedAt(Long storeId) {
        return storeStatusHistoryRepository.findAllByStoreIdOrderByIdAsc(storeId).stream()
                .filter(h -> h.getToStatus() == StoreStatus.CLOSED)
                .reduce((first, second) -> second)
                .map(h -> h.getCreatedAt().atZone(KST).toOffsetDateTime())
                .orElse(null);
    }
}
