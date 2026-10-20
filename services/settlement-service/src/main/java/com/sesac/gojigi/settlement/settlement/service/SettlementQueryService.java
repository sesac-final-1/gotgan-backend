package com.sesac.gojigi.settlement.settlement.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.settlement.settlement.dto.response.SellerSettlementsResponse;
import com.sesac.gojigi.settlement.settlement.dto.response.SettlementDetailResponse;
import com.sesac.gojigi.settlement.settlement.dto.response.SettlementListResponse;
import com.sesac.gojigi.settlement.settlement.dto.response.SettlementOutstandingResponse;
import com.sesac.gojigi.settlement.settlement.dto.response.SettlementStatsResponse;
import com.sesac.gojigi.settlement.settlement.dto.response.SettlementSummaryResponse;
import com.sesac.gojigi.settlement.settlement.entity.Settlement;
import com.sesac.gojigi.settlement.settlement.entity.SettlementItem;
import com.sesac.gojigi.settlement.settlement.entity.SettlementStatus;
import com.sesac.gojigi.settlement.settlement.entity.SettlementStore;
import com.sesac.gojigi.settlement.settlement.exception.SettlementErrorCode;
import com.sesac.gojigi.settlement.settlement.exception.SettlementException;
import com.sesac.gojigi.settlement.settlement.repository.SettlementHistoryRepository;
import com.sesac.gojigi.settlement.settlement.repository.SettlementItemRepository;
import com.sesac.gojigi.settlement.settlement.repository.SettlementRepository;
import com.sesac.gojigi.settlement.settlement.repository.SettlementStoreRepository;
import com.sesac.gojigi.settlement.settlement.util.SettlementWeek;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 정산 조회 — 관리자 목록 · 상세(STL-05), 판매자 내역(STL-07), 대시보드 통계(STL-08) */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettlementQueryService {

    private static final int RECENT_WEEKS = 8;

    private final SettlementRepository settlementRepository;
    private final SettlementItemRepository settlementItemRepository;
    private final SettlementHistoryRepository settlementHistoryRepository;
    private final SettlementStoreRepository settlementStoreRepository;
    private final Clock clock;

    /** 관리자 주차별 목록 — status 로 거를 수 있음 (화면 탭), 합계는 거른 결과 기준 */
    public SettlementListResponse list(SettlementWeek week, Long storeId, SettlementStatus status) {
        List<Settlement> rows = settlementRepository.findByWeekStartOrderByStoreIdAsc(week.start()).stream()
                .filter(s -> storeId == null || s.getStoreId().equals(storeId))
                .filter(s -> status == null || s.getStatus() == status)
                .toList();
        Map<Long, SettlementStore> stores = stores(rows);
        List<SettlementSummaryResponse> summaries = rows.stream()
                .map(s -> SettlementSummaryResponse.of(s, stores.get(s.getStoreId()))).toList();
        return new SettlementListResponse(week.isoWeek(), SettlementListResponse.Totals.of(summaries), summaries);
    }

    /** 관리자 상세 */
    public SettlementDetailResponse detail(Long settlementId) {
        return detail(load(settlementId));
    }

    /** 판매자 상세 — 내 스토어 정산서만 (다른 스토어면 403) */
    public SettlementDetailResponse detailForStore(Long settlementId, Long storeId) {
        Settlement settlement = load(settlementId);
        requireOwner(settlement, storeId);
        return detail(settlement);
    }

    /** 판매자 내역 — 요약 + 주차별 목록(최신순) */
    public SellerSettlementsResponse listForStore(Long storeId) {
        requireStore(storeId);
        List<Settlement> rows = settlementRepository.findByStoreIdOrderByWeekStartDesc(storeId);
        SettlementStore store = settlementStoreRepository.findByStoreId(storeId).orElse(null);
        SettlementStatsResponse.StoreStats stats = storeStats(storeId, rows);
        List<SellerSettlementsResponse.AppliedRate> rates = settlementItemRepository.findTop50ByStoreIdOrderByConfirmedAtDesc(storeId)
                .stream().collect(Collectors.toMap(SettlementItem::getCategoryId, SettlementItem::getCommissionRate,
                        (latest, older) -> latest, LinkedHashMap::new))
                .entrySet().stream().map(e -> new SellerSettlementsResponse.AppliedRate(e.getKey(), e.getValue())).toList();
        long paidRecent = stats.recentPaid().stream().mapToLong(SettlementStatsResponse.Paid::payoutAmt).sum();
        return new SellerSettlementsResponse(
                new SellerSettlementsResponse.Summary(stats.nextPayout(), paidRecent, stats.pendingPayoutAmt(), rates),
                rows.stream().map(s -> SettlementSummaryResponse.of(s, store)).toList());
    }

    /** 대시보드 통계 — storeId 가 있으면 그 스토어, 없으면 플랫폼 전체 */
    public SettlementStatsResponse stats(Long storeId) {
        if (storeId != null) {
            return new SettlementStatsResponse(storeStats(storeId, settlementRepository.findByStoreIdOrderByWeekStartDesc(storeId)), null);
        }
        List<Settlement> unpaid = settlementRepository.findByStatusIn(EnumSet.of(SettlementStatus.SCHEDULED, SettlementStatus.HELD));
        LocalDate monthStart = LocalDate.now(clock).withDayOfMonth(1);
        long paidThisMonth = settlementRepository.findByStatusAndPaidAtGreaterThanEqual(SettlementStatus.PAID, monthStart.atStartOfDay())
                .stream().mapToLong(Settlement::getPayoutAmt).sum();
        long commissionThisMonth = settlementItemRepository
                .findByConfirmedAtGreaterThanEqualAndConfirmedAtLessThan(monthStart.atStartOfDay(), monthStart.plusMonths(1).atStartOfDay())
                .stream().mapToLong(SettlementItem::getCommissionAmt).sum();
        return new SettlementStatsResponse(null, new SettlementStatsResponse.PlatformStats(
                count(unpaid, SettlementStatus.SCHEDULED), sum(unpaid, SettlementStatus.SCHEDULED),
                count(unpaid, SettlementStatus.HELD), sum(unpaid, SettlementStatus.HELD),
                paidThisMonth, commissionThisMonth));
    }

    /** 스토어 미정산 현황 — 폐점 · 판매자 탈퇴 전 확인 (집계 전 구매확정 · 지급예정 · 보류가 모두 0 이어야 clear) */
    public SettlementOutstandingResponse outstanding(Long storeId) {
        List<SettlementItem> unsettled = settlementItemRepository.findByStoreIdAndSettlementIdIsNull(storeId);
        List<Settlement> scheduled = settlementRepository.findByStoreIdAndStatus(storeId, SettlementStatus.SCHEDULED);
        List<Settlement> held = settlementRepository.findByStoreIdAndStatus(storeId, SettlementStatus.HELD);
        return SettlementOutstandingResponse.of(storeId,
                unsettled.size(), unsettled.stream().mapToLong(SettlementItem::payoutAmt).sum(),
                scheduled.size(), scheduled.stream().mapToLong(Settlement::getPayoutAmt).sum(),
                held.size(), held.stream().mapToLong(Settlement::getPayoutAmt).sum());
    }

    /** 판매자 명세서 — 내 스토어 · 지급 완료 정산서만 */
    public Settlement paidSettlementForStore(Long settlementId, Long storeId) {
        Settlement settlement = load(settlementId);
        requireOwner(settlement, storeId);
        if (settlement.getStatus() != SettlementStatus.PAID) {
            throw new SettlementException(SettlementErrorCode.SETTLE_REPORT_NOT_READY);
        }
        return settlement;
    }

    private SettlementStatsResponse.StoreStats storeStats(Long storeId, List<Settlement> rows) {
        List<SettlementItem> pending = settlementItemRepository.findByStoreIdAndSettlementIdIsNull(storeId);
        SettlementStatsResponse.NextPayout next = rows.stream()
                .filter(s -> s.getStatus() != SettlementStatus.PAID)
                .min(Comparator.comparing(Settlement::getScheduledPayDate))
                .map(s -> new SettlementStatsResponse.NextPayout(s.getId(), new SettlementWeek(s.getWeekStart()).isoWeek(),
                        s.getPayoutAmt(), s.getScheduledPayDate(), s.getStatus().name()))
                .orElse(null);
        LocalDate since = SettlementWeek.of(LocalDate.now(clock)).minusWeeks(RECENT_WEEKS).start();
        List<SettlementStatsResponse.Paid> paid = rows.stream()
                .filter(s -> s.getStatus() == SettlementStatus.PAID && !s.getWeekStart().isBefore(since))
                .map(s -> new SettlementStatsResponse.Paid(s.getId(), new SettlementWeek(s.getWeekStart()).isoWeek(),
                        s.getPayoutAmt(), s.getPaidAt().atZone(SettlementWeek.KST).toOffsetDateTime()))
                .toList();
        return new SettlementStatsResponse.StoreStats(storeId, pending.size(),
                pending.stream().mapToLong(SettlementItem::payoutAmt).sum(), next, paid);
    }

    private SettlementDetailResponse detail(Settlement settlement) {
        SettlementStore store = settlementStoreRepository.findByStoreId(settlement.getStoreId()).orElse(null);
        return new SettlementDetailResponse(SettlementSummaryResponse.of(settlement, store),
                settlementItemRepository.findBySettlementIdOrderByConfirmedAtAsc(settlement.getId()).stream()
                        .map(i -> SettlementDetailResponse.Item.of(i, new SettlementWeek(settlement.getWeekStart()))).toList(),
                settlementHistoryRepository.findBySettlementIdOrderByIdAsc(settlement.getId()).stream()
                        .map(SettlementDetailResponse.History::of).toList());
    }

    private Map<Long, SettlementStore> stores(List<Settlement> rows) {
        return rows.stream().map(Settlement::getStoreId).distinct()
                .map(settlementStoreRepository::findByStoreId).flatMap(java.util.Optional::stream)
                .collect(Collectors.toMap(SettlementStore::getStoreId, Function.identity()));
    }

    private Settlement load(Long settlementId) {
        return settlementRepository.findById(settlementId)
                .orElseThrow(() -> new SettlementException(SettlementErrorCode.SETTLE_NOT_FOUND));
    }

    /** 판매자 API — 게이트웨이가 SELLER 를 확인하고, 여기서는 X-Store-Id 로 소유권만 (가이드라인 08장) */
    private static void requireOwner(Settlement settlement, Long storeId) {
        requireStore(storeId);
        if (!Objects.equals(settlement.getStoreId(), storeId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }

    private static void requireStore(Long storeId) {
        if (storeId == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "스토어가 있는 판매자만 볼 수 있습니다.");
        }
    }

    private static long count(List<Settlement> rows, SettlementStatus status) {
        return rows.stream().filter(s -> s.getStatus() == status).count();
    }

    private static long sum(List<Settlement> rows, SettlementStatus status) {
        return rows.stream().filter(s -> s.getStatus() == status).mapToLong(Settlement::getPayoutAmt).sum();
    }
}
