package com.sesac.gojigi.settlement.settlement.service;

import com.sesac.gojigi.settlement.common.event.EventEnvelope;
import com.sesac.gojigi.settlement.common.event.ProcessedEvent;
import com.sesac.gojigi.settlement.common.event.ProcessedEventRepository;
import com.sesac.gojigi.settlement.settlement.dto.event.StoreApprovedPayload;
import com.sesac.gojigi.settlement.settlement.entity.SettlementStore;
import com.sesac.gojigi.settlement.settlement.repository.SettlementStoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** StoreApproved → 정산용 스토어 스냅샷 (스토어명 · 판매자 이메일 · 정산 계좌). 지급 · 명세서 때 store 를 호출하지 않으려고 미리 받아 둔다 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreSnapshotService {

    public enum Result { CREATED, ALREADY_EXISTS, DUPLICATE }

    private final SettlementStoreRepository settlementStoreRepository;
    private final ProcessedEventRepository processedEventRepository;

    @Transactional
    public Result applyApproved(EventEnvelope<StoreApprovedPayload> event) {
        if (processedEventRepository.existsByEventId(event.eventId())) {
            return Result.DUPLICATE;
        }
        StoreApprovedPayload p = event.payload();
        Result result = Result.ALREADY_EXISTS;
        if (!settlementStoreRepository.existsByStoreId(p.storeId())) {   // 1인 1스토어 · 승인 1회 — 이미 있으면 그대로
            StoreApprovedPayload.SettlementAccount account = p.settlementAccount();
            settlementStoreRepository.save(SettlementStore.approved(p.storeId(), p.storeName(), p.sellerEmail(),
                    account == null ? null : account.bank(),
                    account == null ? null : account.number(),
                    account == null ? null : account.holder()));
            result = Result.CREATED;
        }
        processedEventRepository.save(new ProcessedEvent(event.eventId(), event.eventType()));
        log.info("StoreApproved 반영 storeId={} result={} eventId={}", p.storeId(), result, event.eventId());
        return result;
    }
}
