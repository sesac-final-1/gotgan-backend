package com.sesac.gojigi.settlement.settlement.service;

import com.sesac.gojigi.settlement.common.event.EventEnvelope;
import com.sesac.gojigi.settlement.common.event.ProcessedEvent;
import com.sesac.gojigi.settlement.common.event.ProcessedEventRepository;
import com.sesac.gojigi.settlement.settlement.dto.event.StoreStatusChangedPayload;
import com.sesac.gojigi.settlement.settlement.entity.SettlementStore;
import com.sesac.gojigi.settlement.settlement.repository.SettlementStoreRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * StoreStatusChanged 반영 → 지급 보류 / 해제 판단의 기준 상태. 담당 jihoon-devstu
 * SQS 표준 큐는 순서를 보장하지 않으므로 occurredAt 이 마지막 반영값보다 오래된 이벤트는 무시한다 (docs/events.md 순서 규칙, 10/15 A안).
 * StoreStatusChangedListener 가 호출 — STORE_NOT_FOUND 면 리스너가 예외를 던져 메시지를 큐에 돌려 재시도
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreStatusService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul"); // DB DATETIME 은 Asia/Seoul (컨벤션 3-5)

    private final SettlementStoreRepository settlementStoreRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final SettlementCommandService settlementCommandService;

    public enum Result { APPLIED, STALE_IGNORED, DUPLICATE, STORE_NOT_FOUND }

    @Transactional
    public Result applyStatusChanged(EventEnvelope<StoreStatusChangedPayload> event) {
        if (processedEventRepository.existsByEventId(event.eventId())) {
            return Result.DUPLICATE;
        }
        Long storeId = event.payload().storeId();
        if (!settlementStoreRepository.existsByStoreId(storeId)) {
            // StoreApproved 보다 먼저 도착 — 처리 기록을 남기지 않아 재시도 시 다시 반영할 수 있게 한다
            return Result.STORE_NOT_FOUND;
        }
        LocalDateTime occurredAt = event.occurredAt().atZoneSameInstant(KST).toLocalDateTime();
        int updated = settlementStoreRepository.applyStatusIfNewer(storeId, event.payload().status(), occurredAt);
        processedEventRepository.save(new ProcessedEvent(event.eventId(), event.eventType()));
        if (updated == 0) {
            log.info("옛 StoreStatusChanged 무시 storeId={} status={} occurredAt={} eventId={}",
                    storeId, event.payload().status(), event.occurredAt(), event.eventId());
            return Result.STALE_IGNORED;
        }
        // 최신 상태로 반영됐을 때만 정산서 자동 보류 · 해제 (옛 이벤트로 되돌리지 않음)
        settlementCommandService.applyStoreStatus(storeId, !SettlementStore.ACTIVE.equals(event.payload().status()));
        return Result.APPLIED;
    }
}
