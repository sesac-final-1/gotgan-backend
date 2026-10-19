package com.sesac.gojigi.settlement.settlement.service;

import com.sesac.gojigi.settlement.settlement.dto.event.SettlementPaidPayload;
import com.sesac.gojigi.settlement.settlement.entity.Payout;
import com.sesac.gojigi.settlement.settlement.entity.Settlement;
import com.sesac.gojigi.settlement.settlement.entity.SettlementHistory;
import com.sesac.gojigi.settlement.settlement.entity.SettlementHistory.Actor;
import com.sesac.gojigi.settlement.settlement.entity.SettlementItem;
import com.sesac.gojigi.settlement.settlement.entity.SettlementStatus;
import com.sesac.gojigi.settlement.settlement.entity.SettlementStore;
import com.sesac.gojigi.settlement.settlement.exception.SettlementErrorCode;
import com.sesac.gojigi.settlement.settlement.exception.SettlementException;
import com.sesac.gojigi.settlement.settlement.repository.PayoutRepository;
import com.sesac.gojigi.settlement.settlement.repository.SettlementHistoryRepository;
import com.sesac.gojigi.settlement.settlement.repository.SettlementItemRepository;
import com.sesac.gojigi.settlement.settlement.repository.SettlementRepository;
import com.sesac.gojigi.settlement.settlement.repository.SettlementStoreRepository;
import com.sesac.gojigi.settlement.settlement.util.SettlementWeek;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산서 상태를 바꾸는 작업 — 주간 집계(STL-03) · 보류/해제(STL-09) · 지급(STL-06). 상태가 바뀔 때마다 settlement_history 1행.
 * 지급은 정산서 1건 = 트랜잭션 1개 (일괄 지급에서 일부만 실패해도 나머지는 지급 — SettlementPayService).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementCommandService {

    static final String AUTO_HOLD_REASON = "스토어 휴면 · 정지 — 자동 보류";
    static final String AUTO_RELEASE_REASON = "스토어 운영 재개 — 자동 해제";

    private final SettlementRepository settlementRepository;
    private final SettlementItemRepository settlementItemRepository;
    private final SettlementHistoryRepository settlementHistoryRepository;
    private final SettlementStoreRepository settlementStoreRepository;
    private final PayoutRepository payoutRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public record AggregateResult(String week, int created, int updated, int autoHeld) {
    }

    /**
     * 그 주 구매확정분 중 정산서에 아직 연결되지 않은 상품주문을 스토어별로 묶는다. 멱등 — 스토어 × 주차 1건(UK), 두 번 돌려도 같은 결과.
     * 이미 집계된 주에 늦게 들어온 구매확정은 기존 정산서(지급 전)에 더해 금액을 다시 계산. 지급 완료된 주라면 연결하지 않고 경고만.
     */
    @Transactional
    public AggregateResult aggregate(SettlementWeek week, Actor actor) {
        Map<Long, List<SettlementItem>> byStore = settlementItemRepository.findUnassigned(week.from(), week.until()).stream()
                .collect(Collectors.groupingBy(SettlementItem::getStoreId, LinkedHashMap::new, Collectors.toList()));
        int created = 0;
        int updated = 0;
        int autoHeld = 0;
        for (Map.Entry<Long, List<SettlementItem>> entry : byStore.entrySet()) {
            Long storeId = entry.getKey();
            Optional<Settlement> existing = settlementRepository.findByStoreIdAndWeekStart(storeId, week.start());
            if (existing.isPresent() && existing.get().getStatus() == SettlementStatus.PAID) {
                log.warn("지급 완료된 주차에 늦게 들어온 구매확정 — 연결하지 않음 storeId={} week={} items={}",
                        storeId, week.isoWeek(), entry.getValue().size());
                continue;
            }
            Settlement settlement = existing.orElseGet(() -> settlementRepository.save(
                    Settlement.create(storeId, week.start(), week.end(), week.scheduledPayDate())));
            entry.getValue().forEach(item -> item.assignTo(settlement.getId()));
            settlementItemRepository.flush();
            settlement.recalculate(settlementItemRepository.findBySettlementIdOrderByConfirmedAtAsc(settlement.getId()));
            if (existing.isPresent()) {
                updated++;
                continue;
            }
            created++;
            history(settlement, null, actor, "주간 집계");
            if (settlementStoreRepository.findByStoreId(storeId).map(SettlementStore::isPayoutHeld).orElse(false)) {
                changeToHeld(settlement, AUTO_HOLD_REASON, Actor.SYSTEM);
                autoHeld++;
            }
        }
        log.info("주간 집계 week={} actor={} created={} updated={} autoHeld={}", week.isoWeek(), actor, created, updated, autoHeld);
        return new AggregateResult(week.isoWeek(), created, updated, autoHeld);
    }

    /** 관리자 보류 · 해제 (STL-09) */
    @Transactional
    public Settlement hold(Long settlementId, boolean hold, String reason) {
        Settlement settlement = load(settlementId);
        if (hold) {
            if (reason == null || reason.isBlank()) {
                throw new SettlementException(SettlementErrorCode.SETTLE_HOLD_REASON_REQUIRED);
            }
            changeToHeld(settlement, reason, Actor.ADMIN);
        } else {
            settlement.release();
            history(settlement, SettlementStatus.HELD, Actor.ADMIN, reason);
        }
        return settlement;
    }

    /**
     * StoreStatusChanged 반영 직후 — 휴면 · 정지면 지급예정 정산서를 자동 보류, 운영 재개면 "자동 보류"만 자동 해제.
     * 관리자가 직접 건 보류는 관리자가 푼다 (마지막 이력이 SYSTEM 인 것만 해제).
     */
    @Transactional
    public void applyStoreStatus(Long storeId, boolean payoutHeld) {
        if (payoutHeld) {
            settlementRepository.findByStoreIdAndStatus(storeId, SettlementStatus.SCHEDULED)
                    .forEach(s -> changeToHeld(s, AUTO_HOLD_REASON, Actor.SYSTEM));
            return;
        }
        settlementRepository.findByStoreIdAndStatus(storeId, SettlementStatus.HELD).stream()
                .filter(s -> settlementHistoryRepository.findFirstBySettlementIdOrderByIdDesc(s.getId())
                        .map(h -> h.getActorType() == Actor.SYSTEM).orElse(false))
                .forEach(s -> {
                    s.release();
                    history(s, SettlementStatus.HELD, Actor.SYSTEM, AUTO_RELEASE_REASON);
                });
    }

    /** 정산서 1건 지급 — 지급예정 · 스토어 운영중 · 계좌 있음일 때만. 커밋 후 SettlementPaid 발행 */
    @Transactional
    public Settlement payOne(Long settlementId) {
        Settlement settlement = load(settlementId);
        if (settlement.getStatus() != SettlementStatus.SCHEDULED) {
            throw new SettlementException(SettlementErrorCode.SETTLE_INVALID_STATUS);
        }
        SettlementStore store = settlementStoreRepository.findByStoreId(settlement.getStoreId())
                .orElseThrow(() -> new SettlementException(SettlementErrorCode.SETTLE_STORE_NOT_FOUND));
        if (store.isPayoutHeld()) {
            throw new SettlementException(SettlementErrorCode.SETTLE_STORE_PAYOUT_HELD);
        }
        if (store.getBankName() == null || store.getAccountNumber() == null || store.getAccountHolder() == null) {
            throw new SettlementException(SettlementErrorCode.SETTLE_PAYOUT_ACCOUNT_MISSING);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        settlement.markPaid(now);
        payoutRepository.save(Payout.of(settlement, store, now));
        history(settlement, SettlementStatus.SCHEDULED, Actor.ADMIN, null);
        eventPublisher.publishEvent(new SettlementPaidPayload(settlement.getId(), settlement.getStoreId(),
                new SettlementWeek(settlement.getWeekStart()).isoWeek(), settlement.getPayoutAmt(), store.getSellerEmail()));
        return settlement;
    }

    private void changeToHeld(Settlement settlement, String reason, Actor actor) {
        settlement.hold(reason, LocalDateTime.now(clock));
        history(settlement, SettlementStatus.SCHEDULED, actor, reason);
    }

    private void history(Settlement settlement, SettlementStatus from, Actor actor, String reason) {
        settlementHistoryRepository.save(SettlementHistory.of(settlement.getId(), from, settlement.getStatus(), actor, reason));
    }

    private Settlement load(Long settlementId) {
        return settlementRepository.findById(settlementId)
                .orElseThrow(() -> new SettlementException(SettlementErrorCode.SETTLE_NOT_FOUND));
    }
}
