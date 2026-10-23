package com.sesac.gojigi.order.stats.service;

import com.sesac.gojigi.order.claim.entity.ClaimStatus;
import com.sesac.gojigi.order.claim.repository.OrderClaimRepository;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import com.sesac.gojigi.order.order.entity.OrderStatus;
import com.sesac.gojigi.order.order.repository.OrderItemRepository;
import com.sesac.gojigi.order.order.repository.OrderRepository;
import com.sesac.gojigi.order.stats.dto.response.PlatformOrderStatsResponse;
import com.sesac.gojigi.order.stats.dto.response.StoreOrderStatsResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
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
    private static final Set<ClaimStatus> OPEN_CLAIMS = Set.of(ClaimStatus.REQUESTED, ClaimStatus.APPROVED);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderClaimRepository orderClaimRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public StoreOrderStatsResponse getStoreStats(Long storeId) {
        LocalDateTime today = LocalDate.now(clock).atStartOfDay();
        return StoreOrderStatsResponse.of(storeId,
                orderItemRepository.countByStoreIdAndStatus(storeId, OrderItemStatus.PAYMENT_DONE),
                orderItemRepository.countByStoreIdAndStatus(storeId, OrderItemStatus.PREPARING),
                orderClaimRepository.countByOrderItemStoreIdAndStatusIn(storeId, OPEN_CLAIMS),
                orderItemRepository.sumStoreSales(storeId, NOT_SALES, today, today.plusDays(1)));
    }

    @Transactional(readOnly = true)
    public PlatformOrderStatsResponse getPlatformStats() {
        return new PlatformOrderStatsResponse(orderItemRepository.sumItemAmt(NOT_SALES),
                orderRepository.countByStatus(OrderStatus.PAID));
    }
}
