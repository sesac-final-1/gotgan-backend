package com.sesac.gojigi.order.stats.service;

import com.sesac.gojigi.order.claim.entity.ClaimStatus;
import com.sesac.gojigi.order.claim.repository.OrderClaimRepository;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import com.sesac.gojigi.order.order.entity.OrderStatus;
import com.sesac.gojigi.order.order.repository.OrderItemRepository;
import com.sesac.gojigi.order.order.repository.OrderRepository;
import com.sesac.gojigi.order.stats.dto.response.DailyOrderStatsResponse;
import com.sesac.gojigi.order.stats.dto.response.PlatformOrderStatsResponse;
import com.sesac.gojigi.order.stats.dto.response.StoreOrderStatsResponse;
import com.sesac.gojigi.order.stats.repository.OrderStatsRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 통계 (ORD-10 /internal/stats/orders) — 집계는 데이터 주인인 order 가 하고, store · admin 대시보드는 합치기만 한다.
 * order · claim 을 함께 읽으므로 두 도메인 밖의 stats 패키지에 둔다 (claim → order 한 방향 의존 유지).
 */
@Service
@RequiredArgsConstructor
public class OrderStatsService {

    /** 매출 · 거래액에서 빼는 상품주문 — 결제 전 · 취소 완료 · 반품 완료 */
    private static final Set<OrderItemStatus> NOT_SALES = Set.of(OrderItemStatus.ORDERED, OrderItemStatus.CANCELLED,
            OrderItemStatus.RETURNED);
    /** 진행 중 클레임 — 요청됐거나 승인 후 환불 · 재배송 진행 중 */
    private static final Set<ClaimStatus> OPEN_CLAIMS = ClaimStatus.OPEN;
    /**
     * 진행 중 상품주문 — 결제 완료 ~ 배송 완료(구매확정 전) + 취소 · 반품 · 교환 요청 (store 폐점 조건, 10/20 협업요청).
     * 구매확정 · 취소 완료 · 반품 완료는 끝난 주문. 배송 완료를 넣는 이유: 폐점 뒤 구매확정되면 정산 건이 새로 생긴다.
     */
    private static final Set<OrderItemStatus> IN_PROGRESS = Set.of(OrderItemStatus.PAYMENT_DONE,
            OrderItemStatus.PREPARING, OrderItemStatus.SHIPPING, OrderItemStatus.DELIVERED,
            OrderItemStatus.CANCEL_REQUESTED, OrderItemStatus.RETURN_REQUESTED, OrderItemStatus.EXCHANGE_REQUESTED);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderClaimRepository orderClaimRepository;
    private final OrderStatsRepository orderStatsRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public StoreOrderStatsResponse getStoreStats(Long storeId) {
        LocalDateTime today = LocalDate.now(clock).atStartOfDay();
        return new StoreOrderStatsResponse(storeId,
                orderItemRepository.countByStoreIdAndStatus(storeId, OrderItemStatus.PAYMENT_DONE),
                orderItemRepository.countByStoreIdAndStatus(storeId, OrderItemStatus.PREPARING),
                orderClaimRepository.countByOrderItemStoreIdAndStatusIn(storeId, OPEN_CLAIMS),
                // 취소 완료(CANCELLED)여도 환불이 끝나지 않았으면(클레임 APPROVED) 진행 중으로 센다
                orderStatsRepository.countInProgress(storeId, IN_PROGRESS, OPEN_CLAIMS),
                orderItemRepository.sumStoreSales(storeId, NOT_SALES, today, today.plusDays(1)));
    }

    /** 오늘(KST) 포함 최근 days 일의 결제일별 거래액 · 주문 수, 오래된 날부터 — 거래가 없는 날은 0. storeId 가 null 이면 전체 */
    @Transactional(readOnly = true)
    public List<DailyOrderStatsResponse> getDailyStats(Long storeId, int days) {
        LocalDate today = LocalDate.now(clock);
        LocalDate first = today.minusDays(days - 1L);
        LocalDateTime from = first.atStartOfDay();
        LocalDateTime to = today.plusDays(1).atStartOfDay();
        Map<LocalDate, Long> tradeAmt = byDate(orderStatsRepository.sumDailyTradeAmt(storeId, NOT_SALES, from, to));
        Map<LocalDate, Long> orderCount = byDate(
                orderStatsRepository.countDailyOrders(storeId, OrderStatus.PAID, NOT_SALES, from, to));
        return first.datesUntil(today.plusDays(1))
                .map(d -> new DailyOrderStatsResponse(d, tradeAmt.getOrDefault(d, 0L), orderCount.getOrDefault(d, 0L)))
                .toList();
    }

    private static Map<LocalDate, Long> byDate(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(r -> (LocalDate) r[0], r -> ((Number) r[1]).longValue()));
    }

    @Transactional(readOnly = true)
    public PlatformOrderStatsResponse getPlatformStats() {
        return new PlatformOrderStatsResponse(orderItemRepository.sumItemAmt(NOT_SALES),
                orderRepository.countByStatus(OrderStatus.PAID));
    }
}
