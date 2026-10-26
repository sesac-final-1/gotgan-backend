package com.sesac.gojigi.settlement.settlement.service;

import com.sesac.gojigi.settlement.common.event.EventOutboxService;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    static final String AUTO_HOLD_REASON = "스토어 정지 · 폐점 — 자동 보류";
    static final String CLOSED_HOLD_REASON = "스토어 폐점 — 자동 보류";
    static final String AUTO_RELEASE_REASON = "스토어 운영 재개 — 자동 해제";

    private final SettlementRepository settlementRepository;
    private final SettlementItemRepository settlementItemRepository;
    private final SettlementHistoryRepository settlementHistoryRepository;
    private final SettlementStoreRepository settlementStoreRepository;
    private final PayoutRepository payoutRepository;
    private final EventOutboxService eventOutboxService;
    private final Clock clock;

    /** carriedOver = 지난 주차(지급 완료 · 정산서 없음)에서 이번 주차 정산서로 이월한 상품주문 수 */
    public record AggregateResult(String week, int created, int updated, int autoHeld, int carriedOver) {
    }

    /**
     * 그 주 구매확정분 중 정산서에 아직 연결되지 않은 상품주문을 스토어별로 묶는다. 멱등 — 스토어 × 주차 1건(UK), 두 번 돌려도 같은 결과.
     * 지난 주차에 늦게 들어온 구매확정(결정 2번 A안): 원래 주 정산서가 지급 전이면 거기에 더해 다시 계산,
     * 지급 완료이거나 정산서가 없으면 이번 주 정산서로 이월(원래 구매확정일은 그대로 — 화면에 "이월 n건").
     * 이번 주 정산서까지 이미 지급됐다면 연결하지 않고 다음 집계에서 이월.
     */
    @Transactional
    public AggregateResult aggregate(SettlementWeek week, Actor actor) {
        Map<Long, List<SettlementItem>> byStore = new LinkedHashMap<>();
        int updated = 0;
        int carriedOver = 0;
        Map<Long, Map<LocalDate, List<SettlementItem>>> late = settlementItemRepository.findUnassignedBefore(week.from()).stream()
                .collect(Collectors.groupingBy(SettlementItem::getStoreId, LinkedHashMap::new, Collectors.groupingBy(
                        i -> SettlementWeek.of(i.getConfirmedAt().toLocalDate()).start(), LinkedHashMap::new, Collectors.toList())));
        for (Map.Entry<Long, Map<LocalDate, List<SettlementItem>>> store : late.entrySet()) {
            for (Map.Entry<LocalDate, List<SettlementItem>> ownWeek : store.getValue().entrySet()) {
                Optional<Settlement> own = settlementRepository.findByStoreIdAndWeekStart(store.getKey(), ownWeek.getKey());
                if (own.isPresent() && own.get().getStatus() != SettlementStatus.PAID) {
                    assignAndRecalculate(own.get(), ownWeek.getValue());
                    updated++;
                    continue;
                }
                byStore.computeIfAbsent(store.getKey(), k -> new ArrayList<>()).addAll(ownWeek.getValue());
                carriedOver += ownWeek.getValue().size();
            }
        }
        settlementItemRepository.findUnassigned(week.from(), week.until())
                .forEach(i -> byStore.computeIfAbsent(i.getStoreId(), k -> new ArrayList<>()).add(i));
        int created = 0;
        int autoHeld = 0;
        for (Map.Entry<Long, List<SettlementItem>> entry : byStore.entrySet()) {
            Long storeId = entry.getKey();
            Optional<Settlement> existing = settlementRepository.findByStoreIdAndWeekStart(storeId, week.start());
            if (existing.isPresent() && existing.get().getStatus() == SettlementStatus.PAID) {
                log.warn("이번 주차 정산서가 이미 지급됨 — 다음 집계에서 이월 storeId={} week={} items={}",
                        storeId, week.isoWeek(), entry.getValue().size());
                continue;
            }
            Settlement settlement = existing.orElseGet(() -> settlementRepository.save(
                    Settlement.create(storeId, week.start(), week.end(), week.scheduledPayDate())));
            assignAndRecalculate(settlement, entry.getValue());
            if (existing.isPresent()) {
                updated++;
                continue;
            }
            created++;
            history(settlement, null, actor, "주간 집계");
            Optional<SettlementStore> snapshot = settlementStoreRepository.findByStoreId(storeId);
            if (snapshot.map(SettlementStore::isPayoutHeld).orElse(false)) {
                changeToHeld(settlement, autoHoldReason(snapshot.get().getStoreStatus()), Actor.SYSTEM);
                autoHeld++;
            }
        }
        log.info("주간 집계 week={} actor={} created={} updated={} autoHeld={} carriedOver={}",
                week.isoWeek(), actor, created, updated, autoHeld, carriedOver);
        return new AggregateResult(week.isoWeek(), created, updated, autoHeld, carriedOver);
    }

    private void assignAndRecalculate(Settlement settlement, List<SettlementItem> items) {
        items.forEach(item -> item.assignTo(settlement.getId()));
        settlementItemRepository.flush();
        settlement.recalculate(settlementItemRepository.findBySettlementIdOrderByConfirmedAtAsc(settlement.getId()));
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
     * StoreStatusChanged 반영 직후 — 운영 중(ACTIVE)이 아니면(정지 · 폐점) 지급예정 정산서를 자동 보류, 운영 재개면 "자동 보류"만 자동 해제.
     * 관리자가 직접 건 보류는 관리자가 푼다 (마지막 이력이 SYSTEM 인 것만 해제). 폐점은 다시 ACTIVE 가 되지 않으므로 자동 해제도 없다.
     */
    @Transactional
    public void applyStoreStatus(Long storeId, String storeStatus) {
        if (!SettlementStore.ACTIVE.equals(storeStatus)) {
            String reason = autoHoldReason(storeStatus);
            settlementRepository.findByStoreIdAndStatus(storeId, SettlementStatus.SCHEDULED)
                    .forEach(s -> changeToHeld(s, reason, Actor.SYSTEM));
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

    /** 자동 보류 사유 — 이력 · 관리자 화면에 그대로 보이므로 상태에 맞게 (폐점은 따로) */
    static String autoHoldReason(String storeStatus) {
        return SettlementStore.CLOSED.equals(storeStatus) ? CLOSED_HOLD_REASON : AUTO_HOLD_REASON;
    }

    /** 정산서 1건 지급 — 지급예정 · 스토어 운영중 · 계좌 있음일 때만. SettlementPaid 는 아웃박스에 남기고 커밋 후 발행 */
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
        // 지급과 같은 트랜잭션에 이벤트를 남긴다 — 커밋 후 발행, 실패하면 아웃박스 발행기가 다시 (V7)
        // 명세서 Lambda 가 다른 곳을 조회하지 않도록 항목까지 담는다 (10/21 방식 B)
        eventOutboxService.enqueue("SettlementPaid", String.valueOf(settlement.getId()), SettlementPaidPayload.of(settlement, store,
                settlementItemRepository.findBySettlementIdOrderByConfirmedAtAsc(settlement.getId())));
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
